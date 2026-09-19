# api/routes.py
from fastapi import APIRouter, Request, Form, File, UploadFile, WebSocket, WebSocketDisconnect
from fastapi.responses import StreamingResponse, JSONResponse, Response
import asyncio
import time
import os
import json
import queue as _queue_mod
from pathlib import Path

router = APIRouter()


def _region_value(v: str, default: float) -> float:
    """把表单里的区域值解析成 0~1 比例。

    空值 / 非法值 / 负数（旧版用 -1 表示“该边界未指定”）/ 大于 1 的值
    （旧前端的像素语义，如 800）一律回退到默认边界——因为 ADB.找图 / 找字 /
    获取截图 只认比例，这类值会被当成比例再乘屏幕宽高，坐标偏移会飞出屏幕。
    """
    try:
        f = float(str(v).strip())
    except (TypeError, ValueError):
        return default
    return f if 0.0 <= f <= 1.0 else default


def _parse_region_str(raw: str):
    """把脚本生成器里 "0.5,0,-1,-1" 这类区域字符串规范成 4 个 0~1 比例。

    返回 None 表示“没填 / 解析不出 4 段”（生成的代码干脆不带区域参数）。
    旧写法里的 -1 按所在位置归一化成 0 或 1，绝不原样生成到代码里——
    否则 x2=-1 会被当成比例，坐标偏移直接错到屏幕外。
    """
    raw = (raw or "").strip()
    if not raw or raw == "-1,-1,-1,-1":
        return None
    parts = [p.strip() for p in raw.split(",")]
    if len(parts) != 4:
        return None
    return (_region_value(parts[0], 0.0), _region_value(parts[1], 0.0),
            _region_value(parts[2], 1.0), _region_value(parts[3], 1.0))

# --- 导入模块 ---
from module import adb_stream
from module.logmanager import ws_manager
from module import task_manager
from module.adb import ADB  # 你原有的 ADB 类


# ===============================================================================================================================
# 设备操作接口
# ===============================================================================================================================
# --- 设备与流 ---
@router.get("/api/get_devices")
async def api_get_devices(hint: str = ""):
    # hint: 前端最近使用的设备地址，供无线调试掉线时自动 adb connect 拉回
    return {"devices": adb_stream.get_devices(hint.strip() or None)}

@router.get("/api/stream_status")
def get_stream_status(device_name: str = None):
    """返回指定设备的视频流连接状态"""
    return JSONResponse(content=adb_stream.stream_manager.get_status(device_name))

@router.post("/api/set_stream_interval")
async def set_stream_interval(request: Request):
    """设置截图间隔（秒），范围0.01-0.6"""
    data = await request.json()
    interval = data.get("interval", 0.3)
    adb_stream.stream_manager.set_screenshot_interval(interval)
    return {"success": True, "message": f"截图间隔已设置为 {interval:.2f} 秒"}

@router.get("/api/device_resolution")
def get_device_resolution(device_name: str):
    """获取设备的物理分辨率"""
    import subprocess
    try:
        result = subprocess.run(
            ["adb", "-s", device_name, "shell", "wm", "size"],
            capture_output=True, text=True, timeout=2
        )
        output = result.stdout.strip()
        if 'Physical size:' in output:
            size_str = output.split('Physical size:')[1].strip()
            w, h = map(int, size_str.split('x'))
            return {"success": True, "width": w, "height": h}
        elif 'size:' in output:
            size_str = output.split('size:')[1].strip()
            w, h = map(int, size_str.split('x'))
            return {"success": True, "width": w, "height": h}
        return {"success": False, "message": "无法获取分辨率"}
    except Exception as e:
        return {"success": False, "message": str(e)}

@router.get("/api/start_stream")
def start_stream(device_name: str):
    adb_stream.stream_manager.start(device_name)
    return {"success": True, "message": "流正在启动..."}

@router.get("/api/stream")
def video_stream(device: str):
    def generate():
        timeout_count = 0
        max_timeout = 10
        while True:
            try:
                frame_bytes = adb_stream.stream_manager.get_frame_jpeg(device)
                timeout_count = 0
                yield (b'--frame\r\n'
                       b'Content-Type: image/jpeg\r\n\r\n' + frame_bytes + b'\r\n\r\n')
            except ValueError:
                timeout_count += 1
                if timeout_count >= max_timeout:
                    break
                yield (b'--frame\r\n'
                       b'Content-Type: image/jpeg\r\n\r\n' + b'' + b'\r\n\r\n')
            except Exception as e:
                print(f"[STREAM ERROR] {e}")
                timeout_count += 1
                if timeout_count >= max_timeout:
                    break
                time.sleep(0.5)
            time.sleep(0.033)
    return StreamingResponse(generate(), media_type="multipart/x-mixed-replace; boundary=frame")

@router.get("/api/current_frame")
def get_current_frame(device_name: str):
    try:
        jpeg_bytes = adb_stream.stream_manager.get_frame_jpeg(device_name)
        return Response(content=jpeg_bytes, media_type="image/jpeg")
    except ValueError:
        return JSONResponse(status_code=400, content={"success": False, "message": "画面未加载"})

# --- 控制与输入（统一接口，按 action 分发）---
@router.post("/api/input")
async def handle_input(
    device_name: str = Form(...), action: str = Form(...),
    x1: int = Form(0), y1: int = Form(0),
    x2: int = Form(0), y2: int = Form(0), duration: int = Form(0),
    points: str = Form(''),      # JSON 轨迹点数组，仅 action=swipe_path 使用
    delay: float = Form(0.01)    # 仅 action=swipe_path 使用
):
    """
    统一输入接口，按 action 分发到 ADB 类的对应方法（均用 uiautomator2 的 touch API，更拟人）：
    - tap:        简单点击(x1, y1)
    - longpress:  长按(x1, y1, duration/1000)   前端 duration 为毫秒，内部转秒
    - swipe:      滑动(x1, y1, x2, y2)          贝塞尔曲线轨迹（比 input swipe 直线更拟人）
    - swipe_path: 曲线滑动(points, delay)        前端手绘轨迹点回放
    """
    try:
        device = ADB(device_name)
        if action == "tap":
            await asyncio.to_thread(device.简单点击, x1, y1)
        elif action == "longpress":
            # 前端 duration 为毫秒，长按方法接收秒
            await asyncio.to_thread(device.长按, x1, y1, duration / 1000.0)
        elif action == "swipe":
            # duration(毫秒) 转秒作为 hold：按下后先停留再滑动（支持前端"长按拖动"，按住不松）
            await asyncio.to_thread(device.滑动, x1, y1, x2, y2, hold=duration / 1000.0)
        elif action in ("gesture_down", "gesture_move", "gesture_up"):
            # 实时手势流（dev 页拖动遥控用）：按下/移动/抬起分多次独立请求发送，
            # 服务端同一设备由触摸锁串行执行，保证顺序与手势完整性
            if action == "gesture_down":
                await asyncio.to_thread(device.触摸按下, x1, y1)
            elif action == "gesture_move":
                await asyncio.to_thread(device.触摸移动, x1, y1)
            else:
                await asyncio.to_thread(device.触摸抬起, x1, y1)
        elif action == "swipe_path":
            try:
                pts = json.loads(points) if points else []
            except Exception:
                return JSONResponse(status_code=400, content={"success": False, "message": "points 不是合法的 JSON"})
            clean_pts = []
            for p in pts:
                if not (isinstance(p, (list, tuple)) and len(p) >= 2):
                    continue
                # 兼容 [x, y] 和 [x, y, t]（t 为相对起点毫秒时间戳，缺失时后端均匀回放）
                clean_pts.append([int(round(float(p[0]))), int(round(float(p[1]))),
                                  float(p[2]) if len(p) >= 3 else None])
            if len(clean_pts) < 2:
                return JSONResponse(status_code=400, content={"success": False, "message": "有效轨迹点不足"})
            await asyncio.to_thread(device.滑动轨迹, clean_pts)
        else:
            return JSONResponse(status_code=400, content={"success": False, "message": f"未知 action: {action}"})
        return {"success": True, "message": "操作已发送"}
    except Exception as e:
        return JSONResponse(status_code=500, content={"success": False, "message": str(e)})

# --- 保存截图 ---
@router.post("/api/save_screenshot")
async def save_screenshot(
    folder_path: str = Form(''),
    file_name: str = Form(''),
    screen_width: str = Form(''),
    screen_height: str = Form(''),
    image: UploadFile = File(...)
):
    try:
        if not file_name:
            return JSONResponse(status_code=400, content={"success": False, "message": "文件名不能为空"})

        # 默认保存到项目目录下的 tasks/tmp 目录
        if not folder_path or folder_path == './tasks/tmp':
            folder_path = str(Path(__file__).resolve().parent.parent / "tasks" / "tmp")
        if not os.path.exists(folder_path):
            os.makedirs(folder_path, exist_ok=True)

        # 生成文件名：{用户输入}_{屏幕宽度}x{屏幕高度}.png
        if screen_width and screen_height:
            final_name = f"{file_name}_{screen_width}x{screen_height}.png"
        else:
            return JSONResponse(status_code=500, content={"success": False, "message": "未获取到设备屏幕尺寸"})

        filepath = os.path.join(folder_path, final_name)

        with open(filepath, "wb") as buffer:
            buffer.write(await image.read())
        return {"success": True, "message": "保存截屏成功", "path": filepath}
    except Exception as e:
        return JSONResponse(status_code=500, content={"success": False, "message": str(e)})
    
# ---- 找图/找字接口 ----
@router.post("/api/find_image")
async def find_image(
    device_name: str = Form(''),
    image: UploadFile = File(None),
    sim: float = Form(0.90),
    priority_corner: str = Form('tl'),
    screen_width: str = Form(''),
    screen_height: str = Form(''),
    x1: str = Form('0'),
    y1: str = Form('0'),
    x2: str = Form('1'),
    y2: str = Form('1')
):
    # 区域一律 0~1 比例；-1 / 空值 / 非法值交给 _region_value 与 ADB._归一化区域 兜底成全屏
    x1_val = _region_value(x1, 0.0)
    y1_val = _region_value(y1, 0.0)
    x2_val = _region_value(x2, 1.0)
    y2_val = _region_value(y2, 1.0)

    device = ADB(device_name)

    img_bytes = None
    if image and image.filename:
        img_bytes = await image.read()

    # 上传模板的基准分辨率（这张图是在多大分辨率下截的）：前端框选时传当前视频流分辨率、
    # 上传本地文件时传文件名里的 _WxH；两者都没有就用当前设备分辨率兜底。
    # 后端要靠“模板基准分辨率 vs 当前截图分辨率”算缩放比，决定该放大模板还是放大截图；
    # 缺了它会退回默认基准 1920x1080，在非 1920 设备上走错匹配分支。
    base_res = None
    try:
        if screen_width and screen_height:
            base_res = (int(float(screen_width)), int(float(screen_height)))
    except (TypeError, ValueError):
        base_res = None
    if base_res is None:
        try:
            if device.width and device.height:
                base_res = (int(device.width), int(device.height))
        except (TypeError, ValueError):
            base_res = None

    # 同步耗时操作（截图/预加载/模板匹配）放入线程池，避免阻塞事件循环导致视频流卡住
    def _do_find_image():
        if img_bytes:
            device.图片预加载(img_bytes, base_resolution=base_res)
        else:
            main_img = device.获取截图(x1_val, y1_val, x2_val, y2_val)
            # 模板就是刚截的画面（区域图尺寸≠屏幕分辨率，基准要用屏幕分辨率），缩放比恰为 1.0
            device.图片预加载(main_img, base_resolution=base_res)
        return device.找图(sim=sim, priority_corner=priority_corner, x1=x1_val, y1=y1_val, x2=x2_val, y2=y2_val)
    
    result = await asyncio.to_thread(_do_find_image)
    print(result)
    return {"result": str(result)}

@router.post("/api/ocr_text")
async def ocr_text(
    device_name: str = Form(''),
    image: UploadFile = File(None),
    target_txt: str = Form(''),
    use_regex: bool = Form(False),
    x1: str = Form('0'),
    y1: str = Form('0'),
    x2: str = Form('1'),
    y2: str = Form('1')
):
    # 区域一律 0~1 比例；-1 / 空值 / 非法值交给 _region_value 与 ADB._归一化区域 兜底成全屏
    x1_val = _region_value(x1, 0.0)
    y1_val = _region_value(y1, 0.0)
    x2_val = _region_value(x2, 1.0)
    y2_val = _region_value(y2, 1.0)

    device = ADB(device_name)

    img_bytes = None
    if image and image.filename:
        img_bytes = await image.read()

    # 同步耗时操作（OCR）放入线程池，避免阻塞事件循环导致视频流卡住
    def _do_ocr():
        if img_bytes:
            return device.找字(Specified_image=img_bytes, target_txt=target_txt, use_regex=use_regex, x1=x1_val, y1=y1_val, x2=x2_val, y2=y2_val)
        return device.找字(target_txt=target_txt, use_regex=use_regex, x1=x1_val, y1=y1_val, x2=x2_val, y2=y2_val)
    
    result = await asyncio.to_thread(_do_ocr)
    print(result)
    return {"result": str(result)}

# ===============================================================================================================================
# 任务执行接口
# ===============================================================================================================================
from module.decorators import register_stop_signal, cleanup_stop_signal, TaskStoppedException, cleanup_timeout, send_email, load_email_config, save_email_config

@router.post("/api/start_task")
async def run_task(request: Request):
    data = await request.json()

    task_name = data.get("task")
    device_id = data.get("device")
    config = data.get("config", {})
    mode = config.get("mode", "less")
    
    if not device_id:
        return JSONResponse(status_code=400, content={"success": False, "message": "设备ID不能为空"})

    try:
        # 动态导入任务类
        import importlib
        module = importlib.import_module(f'tasks.{task_name}')
        TaskClass = getattr(module, f'Task_{task_name}')
        
        # 创建设备对象
        device = ADB(
            device_id,
            mode,
            enable_match_debug=bool(config.get("enable_match_debug", False)),
            task_name=task_name,
        )
        
        # 注册停止信号 (必须)
        register_stop_signal(device_id)
        
        # 实例化任务
        task_instance = TaskClass(device, config)

        # 定义任务包装器
        async def task_wrapper():
            end_status = "正常完成"
            end_err = ""
            try:
                await asyncio.to_thread(task_instance.run)
         
            except TaskStoppedException:
                end_status = "被手动终止"
                ws_manager.log(f"任务 {device_id} 已被终止", "success")
                
            except asyncio.CancelledError:
                end_status = "被取消"
                ws_manager.log(f"任务 {device_id} 被取消", "warning")
                
            except Exception as e:
                end_status = "异常结束"
                end_err = str(e)
                import traceback
                traceback.print_exc()
                ws_manager.log(f"任务异常: {e}", "error")
                
            finally:
                # 【关键清理】
                cleanup_stop_signal(device_id)
                cleanup_timeout(device_id)
                task_manager.active_tasks.pop(device_id, None)
                task_manager.active_names.pop(device_id, None)

                # 任务结束邮件提醒
                try:
                    subject = f"【阴阳师脚本】任务{end_status} - {task_name}（{device_id}）"
                    content_lines = [
                        f"任务名称: {task_name}",
                        f"设备ID: {device_id}",
                        f"结束状态: {end_status}",
                        f"结束时间: {time.strftime('%Y-%m-%d %H:%M:%S')}",
                    ]
                    if end_err:
                        content_lines.append(f"错误信息: {end_err}")
                    content = "\n".join(content_lines)
                    if end_status != "被手动终止":
                        result = send_email(subject, content)
                        if result["success"]:
                            ws_manager.log(f"任务结束邮件已发送至 {load_email_config().get('receiver_email', '')}", "success")
                        else:
                            if result["message"] not in ("任务结束提醒未开启",):
                                ws_manager.log(f"提醒邮件未送达: {result['message']}", "warning")
                except Exception as mail_ex:
                    ws_manager.log(f"发送提醒邮件出错: {mail_ex}", "warning")
        
        # 创建并注册 asyncio 任务
        task_obj = asyncio.create_task(task_wrapper())
        task_manager.register_task(device_id, task_obj, task_name)

        # 记录本次成功启动的任务参数（供悬浮窗遥控页回填/一键重跑）
        task_manager.save_recent_config(device_id, task_name, config)

        return {"success": True, "message": "任务已开始"}
        
    except Exception as e:
        import traceback
        traceback.print_exc()
        return JSONResponse(
            status_code=500, 
            content={"success": False, "message": f"启动失败: {str(e)}"}
        )

# --- 任务状态查询 ---
@router.get("/api/task_status")
def get_task_status(device: str = "", since_seq: int = 0):
    """查询任务运行状态。
    传 device 查指定设备；不传时返回「是否有任一设备在运行任务」
    （悬浮球光环据此判断任务执行中，无需感知具体被控设备）。

    since_seq：调用方带上「已消费的日志序号」，接口顺带增量返回新日志，
    供悬浮球在「隐藏成线」时弹日志气泡——复用这条轮询通道即可，
    不必另建 WebSocket（悬浮小窗 WebView 收起时 onPause，长连接会被挂起）。"""
    if not device:
        running, name = False, None
        for dev_id in list(task_manager.active_tasks.keys()):
            running, name = task_manager.is_running(dev_id)
            if running:
                break
    else:
        running, name = task_manager.is_running(device)

    return {
        "running": running,
        "task_name": name,
        "log_seq": ws_manager.seq,
        "logs": ws_manager.logs_since(since_seq),
    }

# --- 停止任务 ---
from module.decorators import trigger_stop_signal

@router.post("/api/stop_task")
async def stop_task_api(request: Request):
    body = await request.json()
    device_id = body.get("device")
    
    if not device_id:
        return {"success": False, "message": "缺少设备ID"}
    
    # 触发停止信号（用于优雅停止，让代码清理资源）
    trigger_stop_signal(device_id)
    
    return {"success": True, "message": "终止指令已发送"}


# --- 悬浮窗遥控页：最近任务参数 ---
@router.get("/api/task_last_configs")
def get_task_last_configs(device: str = None):
    """获取某设备各任务最近一次的启动参数（悬浮窗遥控页用它按上次参数回填/重跑）"""
    if not device:
        return {"success": False, "configs": {}}
    return {"success": True, "configs": task_manager.get_recent_configs(device)}


# ===============================================================================================================================
# 邮件提醒接口
# ===============================================================================================================================
@router.get("/api/email_config")
def get_email_config_api():
    """读取邮件配置"""
    cfg = load_email_config()
    # 不返回授权码明文，只返回掩码是否设置
    masked_cfg = {
        "enabled": cfg.get("enabled", False),
        "smtp_server": cfg.get("smtp_server", ""),
        "smtp_port": cfg.get("smtp_port", 465),
        "use_ssl": cfg.get("use_ssl", True),
        "sender_email": cfg.get("sender_email", ""),
        "auth_code_set": bool(cfg.get("auth_code")),
        "receiver_email": cfg.get("receiver_email", ""),
    }
    return JSONResponse(content=masked_cfg)

@router.post("/api/email_config")
async def save_email_config_api(request: Request):
    """保存邮件配置"""
    try:
        data = await request.json()
        existing = load_email_config()
        # 如果前端没传 auth_code 或者传了空表示沿用旧授权码
        if not data.get("auth_code") and existing.get("auth_code"):
            data["auth_code"] = existing["auth_code"]
        ok = save_email_config(data)
        if ok:
            return {"success": True, "message": "邮件配置已保存"}
        return JSONResponse(status_code=500, content={"success": False, "message": "保存失败"})
    except Exception as e:
        return JSONResponse(status_code=500, content={"success": False, "message": f"保存失败: {str(e)}"})

@router.post("/api/email_test")
async def send_test_email_api(request: Request):
    """发送测试邮件（使用当前已保存的配置）"""
    data = await request.json()
    # 如果前端临时传了配置，临时用一下（不保存）
    cfg = data.get("config")
    if cfg:
        import json as _json
        import smtplib as _smtplib
        from email.mime.text import MIMEText as _MIMEText
        from email.mime.multipart import MIMEMultipart as _MIMEMultipart
        from email.header import Header as _Header
        if not (cfg.get("smtp_server") and cfg.get("sender_email") and cfg.get("auth_code") and cfg.get("receiver_email")):
            return JSONResponse(status_code=400, content={"success": False, "message": "配置不完整"})
        try:
            msg = _MIMEMultipart()
            msg["From"] = str(_Header(cfg["sender_email"]))
            msg["To"] = str(_Header(cfg["receiver_email"]))
            msg["Subject"] = str(_Header("阴阳师脚本 - 测试邮件", "utf-8"))
            msg.attach(_MIMEText("这是一封测试邮件，如果您看到此邮件说明 SMTP 配置成功。\n\n来自 KaguraX", "plain", "utf-8"))
            smtp_server = cfg["smtp_server"]
            smtp_port = int(cfg.get("smtp_port", 465))
            use_ssl = bool(cfg.get("use_ssl", True))
            if use_ssl:
                server = _smtplib.SMTP_SSL(smtp_server, smtp_port, timeout=30)
            else:
                server = _smtplib.SMTP(smtp_server, smtp_port, timeout=30)
                server.starttls()
            try:
                server.login(cfg["sender_email"], cfg["auth_code"])
                server.sendmail(cfg["sender_email"], cfg["receiver_email"].split(","), msg.as_string())
            finally:
                server.quit()
            return {"success": True, "message": "测试邮件发送成功，请检查邮箱（含垃圾箱）"}
        except Exception as e:
            return JSONResponse(status_code=500, content={"success": False, "message": f"发送失败: {str(e)}"})
    else:
        result = send_email(
            "阴阳师脚本 - 测试邮件",
            "这是一封测试邮件，如果您看到此邮件说明 SMTP 配置成功。\n\n来自 KaguraX"
        )
        if result["success"]:
            return result
        return JSONResponse(status_code=500, content=result)


# ===============================================================================================================================
# 自定义任务辅助接口
# ===============================================================================================================================
PROJECT_ROOT = os.path.dirname(os.path.dirname(__file__))
TMP_IMG_DIR = os.path.join(PROJECT_ROOT, "tasks", "tmp")
DEBUG_MATCH_DIR = os.path.join(PROJECT_ROOT, "debug", "match")


def _collect_image_paths(roots):
    images = []
    seen = set()
    for root in roots:
        if not root or not os.path.exists(root):
            continue
        for dirpath, _, files in os.walk(root):
            for f in files:
                ext = os.path.splitext(f)[1].lower()
                if ext not in (".png", ".jpg", ".jpeg", ".bmp"):
                    continue
                abs_path = os.path.join(dirpath, f)
                rel = os.path.relpath(abs_path, PROJECT_ROOT)
                rel = rel.replace("\\", "/")
                if rel not in seen:
                    seen.add(rel)
                    images.append(rel)
    images.sort()
    return images


@router.get("/api/list_tmp_images")
def list_tmp_images():
    """列出任务模板目录、debug 目录和 tmp 目录下的图片文件（含子目录）"""
    roots = [TMP_IMG_DIR, DEBUG_MATCH_DIR]
    roots.extend([os.path.join(PROJECT_ROOT, 'tasks')])
    images = _collect_image_paths(roots)
    return JSONResponse(content={"images": images})


@router.get("/api/list_debug_images")
def list_debug_images():
    """列出所有任务目录下 debug 子目录中的图片，用于手机端查看匹配调试图。"""
    roots = [os.path.join(PROJECT_ROOT, 'tasks')]
    debug_roots = []
    for dirpath, _, _ in os.walk(os.path.join(PROJECT_ROOT, 'tasks')):
        if os.path.basename(dirpath) == 'debug':
            debug_roots.append(dirpath)
    roots.extend(debug_roots)
    roots.append(DEBUG_MATCH_DIR)
    images = _collect_image_paths(roots)
    return JSONResponse(content={"images": images})

@router.post("/api/custom_generate_code")
async def custom_generate_code(request: Request):
    """根据前端 steps JSON 生成对应的 Python 代码字符串"""
    try:
        data = await request.json()
        steps = data.get("steps") or []
        code = _steps_to_python(steps, indent=8)
        return {"success": True, "code": code}
    except Exception as e:
        return JSONResponse(status_code=500, content={"success": False, "message": str(e)})

def _steps_to_python(steps: list, indent: int = 4) -> str:
    """把 steps JSON 转成 python 代码"""
    pad = " " * indent
    lines = []
    for i, step in enumerate(steps):
        typ = step.get("type", "")
        params = step.get("params") or {}
        children = step.get("children") or []

        def _s(k, d=""): return str(params.get(k, d))

        if typ == "preload_images":
            raw = _s("paths")
            paths = [p.strip().strip("\"'") for p in __import__("re").split(r"[\n,;，；]+", raw) if p.strip()]
            if not paths:
                continue
            paths_str = ", ".join([f'"{p}"' for p in paths])
            lines.append(f"{pad}n = self.op.图片预加载({paths_str})")
            lines.append(f"{pad}self.op.log(f\"成功预加载 {{n}} 张模板图片\")")
        elif typ == "loop_count":
            count = int(params.get("count", 0))
            if count > 0:
                lines.append(f"{pad}for _ in range({count}):")
            else:
                lines.append(f"{pad}while True:")
            lines.append(f"{pad}    self.op.check_stop()")
            child_code = _steps_to_python(children, indent + 4)
            if child_code:
                lines.append(child_code)
            else:
                lines.append(f"{pad}    pass")
        elif typ == "loop_until_match":
            target_type = params.get("target_type", "image")
            target = _s("target")
            lines.append(f"{pad}while True:")
            lines.append(f"{pad}    self.op.check_stop()")
            child_code = _steps_to_python(children, indent + 4)
            if child_code:
                lines.append(child_code)
            else:
                lines.append(f"{pad}    pass")
            if target_type == "text":
                lines.append(f"{pad}    _last = locals().get('_last', {{}})")
                lines.append(f"{pad}    if any(\"{target}\" in str(k) for k in _last): break")
            else:
                lines.append(f"{pad}    _last = locals().get('_last', {{}})")
                lines.append(f"{pad}    if any(\"{target}\" in str(k) for k in _last): break")
        elif typ == "find_image":
            sim = float(params.get("sim", 0.9))
            corner = params.get("corner", "tl")
            region = _parse_region_str(params.get("region", ""))
            if region is not None:
                x1, y1, x2, y2 = region
                lines.append(f"{pad}_last = self.op.找图(sim={sim}, priority_corner=\"{corner}\", x1={x1}, y1={y1}, x2={x2}, y2={y2})")
            else:
                lines.append(f"{pad}_last = self.op.找图(sim={sim}, priority_corner=\"{corner}\")")
        elif typ == "find_text":
            target = _s("target")
            use_regex = bool(params.get("use_regex", False))
            region = _parse_region_str(params.get("region", ""))
            kwargs = []
            if target:
                kwargs.append(f'target_txt="{target}"')
                kwargs.append(f'use_regex={use_regex}')
            if region is not None:
                x1, y1, x2, y2 = region
                kwargs.append(f'x1={x1}, y1={y1}, x2={x2}, y2={y2}')
            lines.append(f"{pad}_last = self.op.找字({', '.join(kwargs)})")
        elif typ == "if_match":
            kind = params.get("kind", "has")
            target = _s("target")
            lines.append(f"{pad}_last = locals().get('_last', {{}})")
            if kind == "empty":
                lines.append(f"{pad}if not _last:")
            elif kind == "not_empty":
                lines.append(f"{pad}if _last:")
            elif kind == "not_has":
                lines.append(f"{pad}if not any(\"{target}\" in str(k) for k in _last):")
            else:
                lines.append(f"{pad}if any(\"{target}\" in str(k) for k in _last):")
            child_code = _steps_to_python(children, indent + 4)
            if child_code:
                lines.append(child_code)
            else:
                lines.append(f"{pad}    pass")
        elif typ == "click_found":
            target = _s("target")
            if target:
                lines.append(f"{pad}if _last and any(\"{target}\" in str(k) for k in _last):")
                lines.append(f"{pad}    _match = next((k for k in _last if \"{target}\" in str(k)), None)")
                lines.append(f"{pad}    if _match: self.op.点击(*_last[_match][:2])")
            else:
                lines.append(f"{pad}if _last:")
                lines.append(f"{pad}    self.op.点击(*list(_last.values())[0][:2])")
        elif typ == "click":
            x = _s("x", "0.5")
            y = _s("y", "0.5")
            lines.append(f"{pad}self.op.点击({x}, {y})")
        elif typ == "long_press":
            x = _s("x", "0.5")
            y = _s("y", "0.5")
            dur = int(params.get("duration", 1000))
            lines.append(f"{pad}self.op.长按({x}, {y}, {dur}/1000.0)")
        elif typ == "swipe":
            x1 = _s("x1", "0.5")
            y1 = _s("y1", "0.8")
            x2 = _s("x2", "0.5")
            y2 = _s("y2", "0.2")
            dur = int(params.get("duration", 500))
            lines.append(f"{pad}self.op.滑动({x1}, {y1}, {x2}, {y2}, {dur}/1000.0)")
        elif typ == "sleep":
            sec = float(params.get("seconds", 1))
            lines.append(f"{pad}self.op.sleep({sec})")
        elif typ == "reset_timer":
            lines.append(f"{pad}self.op.重置定时器()")
        elif typ == "log":
            msg = _s("msg", "步骤完成")
            lines.append(f'{pad}self.op.log("{msg}")')
        elif typ == "break":
            lines.append(f"{pad}break")
        elif typ == "return":
            lines.append(f"{pad}return")

    return "\n".join(lines)


# ===============================================================================================================================
# scrcpy 视频流（H.264 WebSocket）
# ===============================================================================================================================

@router.get("/api/scrcpy_status")
def scrcpy_status(device_name: str):
    """获取 scrcpy 流状态"""
    return JSONResponse(content=adb_stream.scrcpy_manager.get_status(device_name))

@router.websocket("/ws/scrcpy_stream")
async def scrcpy_stream_ws(websocket: WebSocket):
    """
    scrcpy H.264 视频流 WebSocket 端点
    前端连接后接收：
    1. JSON 元数据（device_info: name, width, height）
    2. 二进制 H.264 Annex B 数据块
    """
    await websocket.accept()

    device = websocket.query_params.get("device")
    if not device:
        await websocket.close(code=1008, reason="缺少 device 参数")
        return

    try:
        # 启动 scrcpy 流
        stream = adb_stream.scrcpy_manager.start(device)

        # 等待流就绪
        for _ in range(50):
            if stream.running:
                break
            await asyncio.sleep(0.1)
        else:
            await websocket.send_json({"type": "error", "message": "scrcpy 启动超时"})
            await websocket.close()
            return

        # 发送元数据
        await websocket.send_json({
            "type": "meta",
            "device_info": stream.device_info,
        })

        # 注册客户端
        q = stream.add_client()

        try:
            while True:
                try:
                    data = await asyncio.to_thread(q.get, True, 0.5)
                    if data:
                        await websocket.send_bytes(data)
                except _queue_mod.Empty:
                    # 检查流是否还在运行
                    if not stream.running:
                        await websocket.send_json({"type": "error", "message": "scrcpy 流已断开"})
                        break
                    # 检查连接是否还在
                    try:
                        await asyncio.wait_for(websocket.receive_text(), timeout=0.01)
                    except asyncio.TimeoutError:
                        pass  # 正常，没有客户端消息
                except WebSocketDisconnect:
                    break
        finally:
            stream.remove_client(q)

    except WebSocketDisconnect:
        pass
    except Exception as e:
        try:
            await websocket.send_json({"type": "error", "message": str(e)[:100]})
            await websocket.close()
        except Exception:
            pass
