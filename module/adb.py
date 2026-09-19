import uiautomator2 as u2
from rapidocr import RapidOCR
from module.decorators import *
from module.logmanager import ws_manager

import cv2
import numpy as np
import os
import time 
import re
import subprocess

from concurrent.futures import ThreadPoolExecutor
import threading
from contextlib import contextmanager


class _ScaledMainPool:
    """
    同一次『找图』调用中，为所有模板共享的『整屏缩放截图』候选池。

    优化前：每张模板一旦原始尺寸匹配不达标，就会各自把整屏截图缩放一遍
    （精确档 + ±0.05 微调网格，最多约 8 个尺寸）再匹配，同一帧里多张模板
    反复做完全相同的整屏缩放。
    优化后：候选尺寸只按设备缩放比算一次，截图也在第一次被用到时才按需缩放
    一次并缓存，所有模板线程共享复用。候选集、顺序、插值方式、映射系数都与
    逐模板计算时保持一致，因此匹配数值/返回结果不变。
    """

    def __init__(self, main_gray, scale_x, scale_y):
        self._main = main_gray
        self._scale_x = scale_x
        self._scale_y = scale_y
        self._lock = threading.Lock()
        self._images = {}
        self.avg_scale = (scale_x + scale_y) / 2
        # 与原逻辑一致的命名：截图被缩小→ scale_down，被放大→ scale_up
        self.kind = 'scale_down' if self.avg_scale > 1.0 else 'scale_up'
        self.entries = self._build_entries(main_gray.shape[1], main_gray.shape[0])

    # entries[idx] = (宽, 高, 缩放系数x, 缩放系数y)
    def _build_entries(self, w_main, h_main):
        entries = []
        if self.avg_scale > 1.0:
            # 方案A：缩小截图到基准分辨率（保持模板原始分辨率）
            sx = 1.0 / self._scale_x
            sy = 1.0 / self._scale_y
            entries.append((int(w_main * sx), int(h_main * sy), sx, sy))
            for dx in np.arange(-0.15, 0.151, 0.05):   # ±0.05 微调网格
                sdx = sx + dx
                if sdx <= 0.1 or sdx >= 0.8:
                    continue
                entries.append((int(w_main * sdx), int(h_main * sdx), sdx, sdx))
        else:
            # 方案B：放大截图到基准分辨率（保持模板原始细节）
            sx = 1.0 / self._scale_x
            sy = 1.0 / self._scale_y
            entries.append((int(w_main * sx), int(h_main * sy), sx, sy))
            for dx in np.arange(-0.15, 0.151, 0.05):
                sdx = sx + dx
                if sdx <= 0.5 or sdx >= 2.0:
                    continue
                dw = int(w_main * sdx)
                # 与原逻辑相同的取整方式（乘法顺序保持一致，避免浮点舍入差一像素）
                dh = int(w_main * sdx * (h_main / w_main))
                entries.append((dw, dh, sdx, sdx))
        return entries

    def image(self, idx):
        """按需缩放一次并缓存，供所有模板线程共享复用"""
        img = self._images.get(idx)
        if img is None:
            with self._lock:
                img = self._images.get(idx)
                if img is None:
                    dw, dh, _, _ = self.entries[idx]
                    interp = cv2.INTER_AREA if self.kind == 'scale_down' else cv2.INTER_CUBIC
                    img = cv2.resize(self._main, (dw, dh), interpolation=interp)
                    self._images[idx] = img
        return img


class ADB():
    adb_path = os.path.dirname(__file__)   # 取文件所在文件夹绝对路径
    
    # 用字典代替单一的 _instance，key 是传入的参数，value 是对应的实例
    _instances = {}

    @staticmethod
    def _sanitize_debug_component(value):
        """将任务名/设备名规范成安全的目录名，保留中文和常用字符。"""
        raw = str(value or 'unknown_task').strip()
        if not raw:
            raw = 'unknown_task'
        cleaned = re.sub(r'[\\/:*?"<>|\x00-\x1f\x7f]+', '_', raw)
        cleaned = cleaned.strip('_. ')
        return cleaned or 'unknown_task'

    def _debug_base_dir(self):
        """返回项目根目录下的统一 debug 根目录：debug/<task_name>/"""
        project_root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
        task_name = self._sanitize_debug_component(getattr(self, 'task_name', 'unknown_task'))
        return os.path.join(project_root, 'debug', task_name)

    def __new__(cls, device_id: str, mode: str = "less", max_workers=4, adapt_res_everytime: bool = False, source: str = "server", enable_match_debug: bool = False, task_name: str = ""):
        # 把传入的参数变成一个可哈希的 key（比如元组）
        cache_key = (device_id, mode, max_workers, adapt_res_everytime, source, bool(enable_match_debug), str(task_name))
        
        # 如果这个参数组合没被实例化过，就新建一个
        if cache_key not in cls._instances:
            cls._instances[cache_key] = super().__new__(cls)
        # 返回这个参数对应的唯一实例
        return cls._instances[cache_key]

    def __init__(self, device_id: str, mode: str = "less", max_workers=4, adapt_res_everytime: bool = False, source: str = "server", enable_match_debug: bool = False, task_name: str = ""):
        """
        初始化ADB实例，连接指定设备。
        :param device_id: 设备ID，或"None"表示连接所有设备。
        :param mode: 模式，"less"或"more"。
        :param max_workers: 最大线程数。
        :param adapt_res_everytime: 是否每次找图都适配分辨率。
        :param source: 日志来源，"server"或"client"。
        :param enable_match_debug: 是否在每次命中时保存对应模板的 debug 裁剪图，默认 False。
        :param task_name: 当前任务名，用于统一 debug 根目录：debug/<task_name>/
        :return: ADB实例。
        """
        # 由于 Python 机制，每次 return 缓存实例时，都会强制调用 __init__
        # 所以必须加一个标记，防止同一个实例被重复初始化
        if getattr(self, "_is_initialized", False):
            return
        
        self.device_id = device_id
        self.mode = mode
        self.log_manager = ws_manager
        self.bz = bezierTrajectory()

        self.max_workers = max_workers  
        self.adapt_res_everytime = adapt_res_everytime
        self.enable_match_debug = bool(enable_match_debug)
        self.task_name = self._sanitize_debug_component(task_name or getattr(self, 'task_name', 'unknown_task'))
        self.current_scale = None       # 参考分辨率组的缩放比（无基准信息模板的兜底值）
        self.current_scale_x = None     # 每张模板各自持有 scale_x/scale_y；这两个是"参考组"的值
        self.current_scale_y = None
        self._scale_correction = (1.0, 1.0)  # 实测微调修正因子，叠加在每张模板的名义缩放比上
        self.cached_templates = {}      # 缓存所有模板图的灰度矩阵和原始尺寸
        # 每张模板的“上次命中”缓存：{img_name: {'x','y'(全屏绝对坐标),'w','h'(实测屏幕尺寸),'validated','miss'}}
        # 用于连续帧局部优先搜索 + 记录实测缩放，分辨率变化或重新预加载时清空
        self._find_cache = {}
        self.executor = ThreadPoolExecutor(max_workers=self.max_workers) # 复用线程池，避免高频创建销毁
        
        # ORB 检测器缓存，避免重复初始化
        self._orb_detector = None
        self._orb_bf_matcher = None

        # 模板金字塔缓存，预先计算多个缩放级别的模板
        self._pyramid_cache = {}

        # 触摸/点击串行锁 + u2 连接时间戳：
        # 1) 同一设备所有触摸/点击动作串行，避免 down/move/up 被不同来源交错；
        # 2) 距上次连接超时后自动重建，避免长跑后 u2 会话"静默失效"（指令 200 但设备无反应）。
        self._touch_lock = threading.Lock()
        self._touch_connected_at = 0.0
        self._touch_refresh_sec = 60.0

        try:
            self.d = self._u2_connect()
            self._touch_connected_at = time.time()
            self.engine = RapidOCR()

            # 标记当前这个实例已经初始化完毕
            self._is_initialized = True
        except Exception as e:
            self.log(f'设备初始化失败, 请检查设备是否连接。原始错误: {e}', "error")
            # 从缓存字典里删掉这个失败的实例
            # 修复缓存键不一致问题，确保设备初始化失败时能正确清理缓存
            cache_key = (device_id or "None", mode, max_workers, adapt_res_everytime, source)
            ADB._instances.pop(cache_key, None)
            raise Exception(f"设备 {device_id} 初始化失败") 

        # 获取并更新设备分辨率(竖屏状态)
        self.width, self.height = self.d.window_size()
        self.log(f'设备初始化完成: 宽:{self.width}, 高:{self.height}', 'info')

    # ============================================================================================
    # u2 连接管理 + 实时手势
    # 长时间运行后 uiautomator 会话会"静默失效"（touch 调用返回成功但设备端无响应），
    # 因此在触摸/点击前若距上次连接超过阈值则重建连接；同一设备的触摸/点击用一把锁串行，
    # 保证 down/move/up 手势序列完整不被不同来源交错。
    # ============================================================================================

    def _adb_ensure_online(self):
        """adb 传输层兜底：无线(ip:port)在 u2 建连前先幂等 adb connect，
        避免 u2 建连撞上 adb server 里的瞬时断线/offline 状态"""
        if ':' not in self.device_id:
            return
        try:
            subprocess.run(['adb', 'connect', self.device_id],
                           capture_output=True, text=True, timeout=6)
        except Exception:
            pass

    def _u2_connect(self):
        """按设备ID建立新的 uiautomator2 连接（无线网络偶发断线时自动重试）"""
        if self.device_id == "None":
            return u2.connect()
        if ':' not in self.device_id and self.device_id.isdigit():
            target = f'127.0.0.1:{self.device_id}'
        else:
            target = self.device_id
        last_err = None
        for attempt in range(3):
            try:
                self._adb_ensure_online()
                return u2.connect(target)
            except Exception as e:
                last_err = e
                if attempt < 2:
                    time.sleep(0.8 * (attempt + 1))
        raise last_err

    @contextmanager
    def _touch_guard(self, refresh=True):
        """获取触摸锁；refresh=True 时若连接超时则先重建 u2 连接。动作序列期间持锁，防止交错。"""
        self._touch_lock.acquire()
        try:
            if refresh and time.time() - self._touch_connected_at >= self._touch_refresh_sec:
                self.d = self._u2_connect()   # 重建失败会抛异常，锁随后释放
                self._touch_connected_at = time.time()
            yield
        finally:
            self._touch_lock.release()

    def 触摸按下(self, x: int, y: int):
        """实时手势：手指按下（拖动手势起点，按需重建连接）"""
        with self._touch_guard():
            self.d.touch.down(x, y)

    def 触摸移动(self, x: int, y: int):
        """实时手势：手指移动到指定坐标（拖动过程高频调用）"""
        with self._touch_guard(refresh=False):
            self.d.touch.move(x, y)

    def 触摸抬起(self, x: int, y: int):
        """实时手势：手指抬起（手势终点）"""
        with self._touch_guard(refresh=False):
            self.d.touch.up(x, y)

    # 封装module/decorators.py里中断函数的方法
    def sleep(self, seconds: float):
        """
        可中断的睡眠函数，支持在睡眠过程中被外部中断。
        :param seconds: 需要睡眠的秒数
        """
        interruptible_sleep(seconds, self)

    def check_stop(self):
        """
        检查是否需要中断当前任务，如果需要则抛出异常。
        """
        check_stop(self)

    # 封装module/logmanager.py里日志函数的方法
    def log(self, message: object, level: str = "info", source: str = ''):
        """
        专用的日志函数，会同时输出到本地终端 + 推送到前端 WebSocket
        - message: 日志内容
        - level: info / debug / warning / error
        - source: 日志来源，默认设备ID
        """
        # 调用 ws_manager
        if self.log_manager:
            self.log_manager.log(message, level, self.device_id)

    def 设置超时时间(self, seconds: float):
        """
        设置超时时间
        :param seconds: 超时时间，单位秒
        """
        set_timeout(seconds)
    
    def 重置定时器(self):
        '''
        重置定时器
        '''
        reset_timer(self.device_id)

    # ============================================================================================
    # ============================================================================================

    def _get_orb_detector(self):
        """获取缓存的 ORB 检测器实例"""
        if self._orb_detector is None:
            self._orb_detector = cv2.ORB_create(
                nfeatures=800, 
                scaleFactor=1.2, 
                nlevels=8, 
                edgeThreshold=15
            )
        return self._orb_detector
    
    def _get_bf_matcher(self):
        """获取缓存的 BFMatcher 实例"""
        if self._orb_bf_matcher is None:
            self._orb_bf_matcher = cv2.BFMatcher(cv2.NORM_HAMMING, crossCheck=True)
        return self._orb_bf_matcher
    
    def _build_pyramid(self, img_name, temp_info, scale_range=(0.5, 1.5), steps=11):
        """
        构建模板金字塔，预先计算多个缩放级别的模板
        :param scale_range: 缩放范围 (min_scale, max_scale)
        :param steps: 缩放级别数量

        注意：当前流程已不再预构建金字塔——预加载阶段为每张图做 11 次 resize 的开销
        换不来等值收益；适配搜索改为按需精确 resize（更准、总耗时更低）。
        本方法保留，供后续“粗定位再精匹配”的可选加速方案复用。
        """
        if img_name in self._pyramid_cache:
            return
        
        template = temp_info['img']
        h, w = template.shape[:2]
        
        scales = np.linspace(scale_range[0], scale_range[1], steps)
        pyramid = {}
        
        for scale in scales:
            sw = int(w * scale)
            sh = int(h * scale)
            if sw >= 10 and sh >= 10:
                resized = cv2.resize(template, (sw, sh), 
                                    interpolation=cv2.INTER_AREA if scale < 1.0 else cv2.INTER_CUBIC)
                pyramid[scale] = {
                    'img': resized,
                    'w': sw,
                    'h': sh
                }
        
        self._pyramid_cache[img_name] = pyramid
    
    def _get_pyramid_template(self, img_name, scale_x, scale_y):
        """
        从金字塔中获取最接近目标缩放的模板。
        注意：当前流程不构建金字塔，正常调用会直接返回 None，调用方需自行 resize 兜底。
        """
        if img_name not in self._pyramid_cache:
            return None
        
        pyramid = self._pyramid_cache[img_name]
        avg_scale = (scale_x + scale_y) / 2
        
        # 找最接近的缩放级别
        best_scale = None
        min_diff = float('inf')
        for scale in pyramid.keys():
            diff = abs(scale - avg_scale)
            if diff < min_diff:
                min_diff = diff
                best_scale = scale
        
        if best_scale is not None:
            return pyramid[best_scale]
        
        return None
    
    @staticmethod
    def _calc_click_radius(min_side):
        """
        根据目标尺寸计算合理的点击随机偏移半径
        小图用更小的偏移系数，避免点击偏离目标
        """
        if min_side <= 0:
            return 2
        if min_side < 30:
            # 小图（如文字按钮）：偏移半径 = 短边 * 25%，最小 2px
            r = int(min_side * 0.25)
            return max(r, 2)
        elif min_side < 80:
            # 中等图：偏移半径 = 短边 * 40%
            r = int(min_side * 0.4)
            return max(r, 4)
        else:
            # 大图：偏移半径 = 短边 * 50%
            r = int(min_side * 0.5)
            return max(r, 8)
        
    # ==============================英语调用=========================================    
    def launch_app(self, package_name:str):
        '''
        package_name: 应用包名
        '''
        self.启动应用(package_name)

    def close_app(self, package_name:str):
        '''
        package_name: 应用包名
        '''
        self.关闭应用(package_name)

    def screen_off(self):
        '''
        息屏
        '''
        self.息屏()

    def save_screenshot(self, save_path:str=adb_path, x1:float=0, y1:float=0, x2:float=1.0, y2:float=1.0):
        '''
        save_path: 保存截图的路径，默认为 adb_path
        x1, y1, x2, y2: 截图区域坐标，默认为全屏
        '''
        self.截图保存(save_path, x1, y1, x2, y2)

    def get_screenshot(self, x1:float=0, y1:float=0, x2:float=1.0, y2:float=1.0):
        '''
        x1, y1, x2, y2: 截图区域坐标，默认为全屏
        '''
        return self.获取截图(x1, y1, x2, y2)
    
    def simple_click(self, x:int, y:int, r, *els):
        '''
        x, y: 点击坐标
        r: 点击位置，默认为 0
        *els: 其他元素坐标，用于点击多个元素
        '''
        self.简单点击(x, y, r, *els)

    def click(self, x:int, y:int, loc:int, *els):
        '''
        x, y: 点击坐标
        loc: 点击位置，默认为 0
        *els: 其他元素坐标，用于点击多个元素
        '''
        self.点击(x, y, loc, *els)

    def swipe(self, start_x:int, start_y:int, end_x:int, end_y:int, count:int=30, delay:float=0.01, hold:float=0):
        '''
        start_x, start_y: 起始点坐标
        end_x, end_y: 结束点坐标
        count: 滑动次数，默认为 30
        delay: 每次滑动间隔，默认为 0.01
        hold: 滑动后保持时间，默认为 0
        '''
        self.滑动(start_x, start_y, end_x, end_y, count, delay, hold)

    def long_press(self, x:int, y:int, duration:float=1.5, jitter:float=0.1):
        '''
        x, y: 长按坐标
        duration: 持续时间（秒），默认 1.5
        jitter: 随机时间前后偏移值，默认 0.1
        '''
        self.长按(x, y, duration, jitter)

    def input_text(self, txt:str):
        '''
        txt: 要输入的文本
        '''
        self.输入(txt)

    def adb_shell(self, shell:str):
        '''
        shell: 要执行的命令
        '''
        self.adb命令行(shell)
    
    def image_preloading(self, *images):
        '''
        images: 图片路径、图片对象
        '''
        self.图片预加载(*images)
    
    def find_image(self, sim=0.90, x1:float=0, y1:float=0, x2:float=1.0, y2:float=1.0):
        '''
        sim: 图片相似度，默认为 0.90
        x1, y1, x2, y2: 截图区域坐标，默认为全屏, 范围为 0~1
        '''
        return self.找图(sim=sim, x1=x1, y1=y1, x2=x2, y2=y2)
    
    def find_text(self, x1:float=0, y1:float=0, x2:float=1.0, y2:float=1.0, Specified_image=None, target_txt:str='', use_regex: bool = False):
        '''
        x1, y1, x2, y2: 截图区域坐标，默认为全屏，范围为 0~1
        Specified_image: 指定图片（如果不提供则使用当前截图）
        target_txt: 目标文本（如果不提供则返回所有文本框信息），支持正则表达式，返回值为匹配到的文本对应坐标或None
        use_regex: 是否启用正则匹配，默认为 False,;为True则返回值将为匹配到的文本列表，为False则返回匹配到的文本及其坐标
        '''
        return self.找字(x1, y1, x2, y2, Specified_image, target_txt, use_regex)
    

    # ============================================================================================
    # ============================================================================================

    # API zh-CN
    def 启动应用(self, package_name:str):
        self.d.app_start(package_name)
        self.log('启动应用:{0}'.format(package_name), 'info')
        self.重置定时器()
        
    def 关闭应用(self, package_name:str):
        if package_name == 'all':
            self.d.app_stop_all()
            self.log('关闭全部用户应用', 'info')
        else:
            self.d.app_stop(package_name)
            self.log('关闭应用:{0}'.format(package_name), 'info')
        self.重置定时器()
                
    def 息屏(self):
        self.d.screen_off()
        self.log('已息屏', 'info')
        self.重置定时器()
    
    def 截图保存(self, save_path:str=adb_path, x1:float=0, y1:float=0, x2:float=1.0, y2:float=1.0):
        x1, y1, x2, y2 = self._归一化区域(x1, y1, x2, y2)
        x1, y1, x2, y2 = int(self.width*x1), int(self.height*y1), int(self.width*x2), int(self.height*y2)
            
        cv2.imwrite(save_path, self.d.screenshot(format='opencv')[y1:y2, x1:x2, :]) # type: ignore
        self.log('已保存截图到:{0}'.format(save_path), 'info')
        self.重置定时器()
            
    def 获取截图(self, x1:float=0, y1:float=0, x2:float=1.0, y2:float=1.0):
        x1, y1, x2, y2 = self._归一化区域(x1, y1, x2, y2)
        img = self.d.screenshot(format='opencv')
        h, w = img.shape[:2]
        # 设备旋转/分辨率变化时自动校准缓存，避免按旧分辨率裁剪越界返回空图
        if self.width != w or self.height != h:
            self.log(f'分辨率变化，已自动校准: {w}x{h}', 'debug')
            self.width, self.height = w, h
            # 设备可能已重启/分辨率变更：失效缩放比与模板金字塔，强制下次重新适配，
            # 避免按旧缩放比匹配模板导致误匹配
            self.current_scale = None
            self.current_scale_x = None
            self.current_scale_y = None
            self._pyramid_cache.clear()
            # 分辨率变了，上一帧命中的位置/尺寸全部失效
            self._find_cache.clear()
        x1, y1, x2, y2 = int(self.width*x1), int(self.height*y1), int(self.width*x2), int(self.height*y2)

        # 边界钳制，防止坐标越界导致 numpy 切片返回空图
        x1 = max(0, min(int(x1), w))
        x2 = max(0, min(int(x2), w))
        y1 = max(0, min(int(y1), h))
        y2 = max(0, min(int(y2), h))
        if x2 <= x1 or y2 <= y1:
            self.log('获取截图：裁剪区域无效，返回全屏截图', 'debug')
            return img
        return img[y1:y2, x1:x2, :] # type: ignore
        
                     
    def 简单点击(self, x:int, y:int, *els):
        '''
        x,y:点击中心坐标
        els:处理其他无效参数
        '''
        X, Y = x, y
        with self._touch_guard():
            self.d.click(X, Y)
        self.log('简单点击({0}, {1})'.format(X, Y), 'info')
        self.重置定时器()
        
    def 点击(self, center_x: int, center_y: int, loc: int, *els):
        '''
        center_x, center_y: 点击的目标中心坐标
        loc: 允许的随机半径范围（安全边界）
        els: 用于接收处理多余参数（如 w, h, sim 等，此处不使用但保证解包不报错）
        '''
        # 小半径直接点击中心，不做随机偏移
        if loc <= 3:
            with self._touch_guard():
                self.d.touch.down(center_x, center_y)
                time.sleep(np.random.uniform(0.05, 0.15))
                self.d.touch.up(center_x, center_y)
            self.log(f'模拟点击({center_x}, {center_y})', 'info')
            self.重置定时器()
            return
        
        # 均值设为 0：因为我们要的是围绕传入的 center 坐标向四周做正态分布扩散
        # 标准差设为 loc / 3.0：确保 99.7% 的点击点严格落在安全半径内
        sigma = max(1.0, loc / 3.0)
        
        offset_x = np.random.normal(0, sigma)
        offset_y = np.random.normal(0, sigma)
        
        # 安全截断：物理防御，斩断正态分布极端大值引发的出界可能
        offset_x = np.clip(offset_x, -loc, loc)
        offset_y = np.clip(offset_y, -loc, loc)

        # 在中心点基础上施加随机偏移
        X = int(center_x + offset_x)
        Y = int(center_y + offset_y)

        # 执行物理触控
        with self._touch_guard():
            self.d.touch.down(X, Y)
            # 短暂延迟模拟人类点击习惯，增加随机性
            time.sleep(np.random.uniform(0.05, 0.15))
            self.d.touch.up(X, Y)

        self.log(f'模拟点击({X}, {Y})', 'info')
        self.重置定时器()

    def 长按(self, x:int, y:int, duration:float=1.5, jitter:float=0.1):
        '''
        长按指定坐标，持续时间可选，默认1.5秒
        jitter: 随机时间前后偏移值，默认0.1
        '''
        with self._touch_guard():
            self.d.touch.down(x, y)
            duration += np.random.uniform(-jitter, jitter)
            time.sleep(duration)
            self.d.touch.up(x, y)
        self.log(f'模拟长按({x}, {y})', 'info')
        self.重置定时器()

    def 滑动(self, start_x:int, start_y:int, end_x:int, end_y:int, count:int=30, delay:float=0.01, hold:float=0.0):
        '''
        start_x, start_y 为起始坐标,
        end_x, end_y 为终点坐标,
        count 决定了轨迹的细腻程度（点越多越慢越丝滑）
        delay 决定了每次移动的间隔时间（单位：秒，值越大越慢）
        hold: 按下后先停留的时长（单位：秒），用于长按拖动（按住不松再移动），默认 0
        '''
        points = self.bz.trackArray((start_x, start_y), (end_x, end_y), count)['trackArray']
        with self._touch_guard():
            # 首先：手指按下
            self.d.touch.down(start_x, start_y)
            # 长按拖动：按下后先停留 hold 秒再移动，模拟"按住不松手"再拖动
            if hold > 0:
                time.sleep(hold)
            # 其次：遍历轨迹点进行移动
            for x, y in points:
                self.d.touch.move(x, y)
                time.sleep(delay)
            # 最后：手指抬起
            self.d.touch.up(end_x, end_y)
        self.log('模拟滑动{0} {1} -> {2} {3}'.format(start_x, start_y, end_x, end_y), 'info')
        self.重置定时器()

    def 滑动轨迹(self, points):
        '''
        按带时间戳的轨迹点如实回放滑动（用于开发页面回放用户手势，快速滑动/长按拖动统一走这里）。
        :param points: 轨迹点列表，形如 [[x1, y1, t1], [x2, y2, t2], ...]，
                       t 为相对起点的毫秒时间戳（可选；缺失时按每点 20ms 均匀回放，兼容旧调用）
        :return: 回放点数
        实现：touch.down 起点 → 逐点 sleep(Δt) + move → touch.up 终点，
        时间节奏与前端采集完全一致——按住停留多久，设备端就停留多久。
        '''
        if not points or len(points) < 2:
            self.log('轨迹回放：轨迹点不足，至少需要 2 个点', 'warning')
            return 0
        n = len(points)
        prev_t = 0.0
        with self._touch_guard():
            for i, p in enumerate(points):
                x, y = int(round(float(p[0]))), int(round(float(p[1])))
                t = float(p[2]) if len(p) >= 3 and p[2] is not None else i * 20.0
                if i == 0:
                    self.d.touch.down(x, y)
                else:
                    dt = (t - prev_t) / 1000
                    if dt > 0:
                        time.sleep(dt)
                    self.d.touch.move(x, y)
                prev_t = t
            end_x, end_y = int(round(float(points[-1][0]))), int(round(float(points[-1][1])))
            self.d.touch.up(end_x, end_y)
        self.log(f'轨迹回放: {n} 个点, 总时长≈{prev_t/1000:.3f}s, ({int(points[0][0])},{int(points[0][1])}) -> ({end_x},{end_y})', 'debug')
        self.重置定时器()
        return n


    def 输入(self, txt:str):
        self.d.clear_text() # 清除输入框所有内容
        self.d.send_keys(txt)
        self.d.send_action("send") # 根据输入框的需求，自动执行回车、搜索等指令,支持 go, search, send, next, done, previous
        self.log('模拟输入{0}'.format(txt), 'debug')
        self.重置定时器()
        
    def adb命令行(self, shell:str):
        '''
        执行命令行：在adb shell中执行
        示例：input tap 100 100
        '''
        shell = f"adb -s {self.device_id} shell {shell}"
        os.system(shell)
        self.log('执行命令行:{0}'.format(shell), 'debug')
        self.重置定时器()

    def _parse_screen_from_filename(self, filename):
        """
        从文件名提取屏幕参数（格式：xxx_WxH.png）
        :param filename: 文件名
        :return: (screen_width, screen_height)，如果无法解析则返回 (None, None)
        """
        import re
        # 匹配格式：xxx_1920x1080.png 或 xxx_1080x1920.jpg
        pattern = r'_(\d+)x(\d+)\.'
        match = re.search(pattern, filename)
        if match:
            w = int(match.group(1))
            h = int(match.group(2))
            return w, h
        return None, None

    @staticmethod
    def _归一化区域(x1, y1, x2, y2):
        """
        把区域参数统一成 0~1 比例，越界值一律退化为“全屏边界”。

        需要兜底的两种旧写法：
          1. -1：旧版 /api/find_image 的默认值、前端未框选时的占位值，表示“未指定”；
          2. 大于 1 的值：旧前端的像素语义（如 800）。
        这类值被当成比例再乘屏幕宽高后，裁剪会因越界退化成全屏（看着“还能找到”），
        但返回坐标会被加上一个巨大的偏移量（如 width*800），彻底错位。
        起点越界 → 0，终点越界 → 1，最坏情况也只是退化成全屏搜索，不会给出错坐标。
        """
        def _start(v):
            try:
                v = float(v)
            except (TypeError, ValueError):
                return 0.0
            return v if 0.0 <= v <= 1.0 else 0.0

        def _end(v):
            try:
                v = float(v)
            except (TypeError, ValueError):
                return 1.0
            return v if 0.0 <= v <= 1.0 else 1.0

        return _start(x1), _start(y1), _end(x2), _end(y2)

    def _build_resolution_index(self, root_dir):
        """扫描模版目录，按 stem 名称建立分辨率索引：{base_name: [(w,h,path), ...]}。"""
        index = {}
        if not root_dir or not os.path.isdir(root_dir):
            return index
        for dirpath, _, files in os.walk(root_dir):
            for name in files:
                stem, _ = os.path.splitext(name)
                m = re.search(r'_(\d+)x(\d+)$', stem)
                if not m:
                    continue
                base_name = stem[:m.start()]
                w = int(m.group(1))
                h = int(m.group(2))
                index.setdefault(base_name, []).append((w, h, os.path.join(dirpath, name)))
        for base_name in index:
            index[base_name].sort(key=lambda item: (item[0], item[1]))
        return index

    def _find_best_resolution_match(self, image_path, target_w=None, target_h=None):
        """
        搜索同名模板中是否有对应分辨率；没有时返回最近分辨率的模板。
        还支持输入一个不带 _WxH 后缀的基名，例如：
            tasks/xxx/挑战.png
        会自动扫描同目录中同名的所有 _1920x1080 / _1080x2340 版本并挑最接近的一个。
        若同目录没有，进一步扫描其父目录树中的候选分辨率模板库。
        """
        if not isinstance(image_path, str) or not image_path:
            return image_path, []

        if target_w is None or target_h is None:
            target_w, target_h = self.width, self.height
        target_w = int(target_w)
        target_h = int(target_h)

        # 1) 原路径本身存在，直接返回；这样不会破坏明确指定的模板文件
        if os.path.exists(image_path):
            return image_path, [(target_w, target_h, image_path)]

        full_dir = os.path.dirname(image_path)
        file_name = os.path.basename(image_path)
        if not full_dir or not file_name:
            return image_path, []

        stem, ext = os.path.splitext(file_name)
        stem_no_res = re.sub(r'_\d+x\d+$', '', stem)
        exact = os.path.join(full_dir, f"{stem_no_res}_{target_w}x{target_h}{ext}")
        if os.path.exists(exact):
            return exact, [(target_w, target_h, exact)]

        candidates = []
        search_roots = [full_dir]
        if full_dir:
            search_roots.append(os.path.dirname(full_dir))
        seen = set()
        for root in search_roots:
            if not os.path.isdir(root):
                continue
            for dirpath, _, files in os.walk(root):
                for item in files:
                    path = os.path.join(dirpath, item)
                    if path in seen:
                        continue
                    seen.add(path)
                    base, _ = os.path.splitext(item)
                    if not base.startswith(stem_no_res):
                        continue
                    m = re.search(r'_(\d+)x(\d+)$', base)
                    if not m:
                        continue
                    w = int(m.group(1))
                    h = int(m.group(2))
                    score = abs(w - target_w) + abs(h - target_h)
                    ratio_diff = abs((w / max(h, 1)) - (target_w / max(target_h, 1)))
                    candidates.append((score, ratio_diff, w, h, path))

        if not candidates:
            return image_path, []

        candidates.sort(key=lambda item: (item[0], item[1], item[2], item[3]))
        best_path = candidates[0][4]
        best_candidates = [(w, h, p) for _, _, w, h, p in candidates[:5]]
        if self.mode == 'more':
            self.log(f"未找到 {target_w}x{target_h} 对应模板，使用近似分辨率 {candidates[0][2]}x{candidates[0][3]}: {best_path}", 'debug')
            self.log(f"候选分辨率模板: {best_candidates}", 'debug')
        return best_path, best_candidates

    def _save_match_crop(self, screenshot_img, img_name, match_value, save_dir=None):
        """统一保存在项目根目录下 debug/<task_name>/ 下，不再写到模板目录或其 nested debug 子目录。
        例如：debug/突破/奖励_1920x1080.png
        同一模板在同一分辨率下只保留一张，只有当新 score 更高时才覆盖旧图。
        """
        if not getattr(self, 'enable_match_debug', False):
            return None
        if screenshot_img is None or not match_value or len(match_value) < 6:
            return None
        try:
            if save_dir is None:
                save_dir = self._debug_base_dir()
            os.makedirs(save_dir, exist_ok=True)

            center_x, center_y, _, w_match, h_match, score = match_value
            h, w = screenshot_img.shape[:2]
            x1 = max(0, int(center_x - w_match / 2))
            y1 = max(0, int(center_y - h_match / 2))
            x2 = min(w, int(center_x + w_match / 2))
            y2 = min(h, int(center_y + h_match / 2))
            if x2 <= x1 or y2 <= y1:
                return None

            crop = screenshot_img[y1:y2, x1:x2]
            if crop.size == 0:
                return None

            raw_name = os.path.splitext(os.path.basename(img_name))[0]
            raw_name = re.sub(r'_\d+x\d+$', '', raw_name)
            # 仅清理 Windows/路径非法字符，保留中文、字母、数字、下划线和横线；
            # 避免像“奖励_1920x1080.png”这类模板名被错误清洗成“__1920x1080_png”。
            safe_name = re.sub(r'[\\/:*?"<>|\x00-\x1f\x7f]+', '_', raw_name).strip('_. ')
            if not safe_name:
                safe_name = 'template'
            file_name = f"{safe_name}_{self.width}x{self.height}.png"
            output_path = os.path.join(save_dir, file_name)
            info_path = os.path.join(save_dir, f"{safe_name}_{self.width}x{self.height}.txt")

            if os.path.exists(info_path):
                try:
                    with open(info_path, 'r', encoding='utf-8') as f:
                        prev = f.read().strip()
                    prev_score = None
                    for line in prev.splitlines():
                        if line.startswith('score='):
                            try:
                                prev_score = float(line.split('=', 1)[1])
                            except Exception:
                                pass
                    if prev_score is not None and score <= prev_score:
                        return output_path
                except Exception:
                    pass

            cv2.imwrite(output_path, crop)
            info_text = (
                f"template_name={img_name}\n"
                f"task_name={self.task_name}\n"
                f"device_resolution={self.width}x{self.height}\n"
                f"match_center=({int(center_x)}, {int(center_y)})\n"
                f"match_size=({int(w_match)}, {int(h_match)})\n"
                f"score={score:.4f}\n"
                f"crop_box=(x1={x1}, y1={y1}, x2={x2}, y2={y2})\n"
            )
            with open(info_path, 'w', encoding='utf-8') as f:
                f.write(info_text)
            return output_path
        except Exception as e:
            self.log(f"保存匹配截图失败({img_name}): {e}", 'warning')
            return None

    def 图片预加载(self, *images, base_resolution=None):
        '''
        在进入 while 循环前，一次性把所有图片读入内存并转为灰度图。
        避免在 while 循环中频繁进行磁盘 I/O 和色彩空间转换。
        
        调用方式：
            self.op.图片预加载(图片1, 图片2, 图片3, ...)

        支持类型：
            1. 图片路径字符串
            2. cv2 数组
            3. PIL 图片对象

        性能说明（对比旧实现）：
            1. 解码并发执行：读盘 + 解码都发生在 cv2 内部（释放 GIL），多线程有实际收益；
            2. 模板金字塔改为延迟构建：只有分辨率适配的搜索真正用到时才建，
               预加载阶段不再为每张 ≥50x50 的图做 11 次 resize；
            3. 同名换图时一并清空 _pyramid_cache，避免拿旧图的金字塔去匹配新图。
        '''
        if base_resolution is None:
            base_resolution = (self.width, self.height)

        normalized_images = []
        resolution_logs = []
        for img in images:
            if isinstance(img, str):
                chosen_path, candidates = self._find_best_resolution_match(img, *base_resolution)
                normalized_images.append(chosen_path)
                resolution_logs.append((img, chosen_path, candidates))
            else:
                normalized_images.append(img)
                resolution_logs.append((None, None, []))

        if self.mode == 'more':
            for original, chosen, candidates in resolution_logs:
                if not original:
                    continue
                if chosen == original:
                    self.log(f"模板分辨率解析: {original} -> 直接使用原图", 'debug')
                else:
                    self.log(f"模板分辨率解析: {original} -> {chosen} | 候选={candidates}", 'debug')

        self.cached_templates.clear()
        self._find_cache.clear()     # 换了一批模板，旧的命中缓存（名称可能复用但图不同）一并作废
        self._pyramid_cache.clear()  # 金字塔基于模板内容构建，模板换了必须重建，否则匹配的是旧图
        failed_count = 0

        if not images:
            self.log("图片预加载：未传入任何图片", 'warning')
            self.重置定时器()
            return 0

        # 并发解码：分批提交，保证两张图之间仍能响应停止指令（与原逐张 check_stop 的可中断性一致）
        t_start = time.time()
        workers = min(8, max(1, len(normalized_images)))
        with ThreadPoolExecutor(max_workers=workers) as pool:
            for start in range(0, len(normalized_images), workers):
                check_stop(self)
                batch = list(enumerate(normalized_images))[start:start + workers]
                loaded = pool.map(lambda pair: self._load_template(pair[1], pair[0], base_resolution), batch)
                for (idx, img), (img_name, temp_info, warn) in zip(batch, loaded):
                    if img_name is None or temp_info is None:
                        if warn:
                            self.log(warn, 'warning')
                        failed_count += 1
                        continue
                    self.cached_templates[img_name] = temp_info

        decode_cost = time.time() - t_start
        self.重置定时器()
        # 强制重新计算分辨率适配，确保缩放比例与当前屏幕匹配
        self._adapt_resolution()
        elapsed = time.time() - t_start
        # 级别用 info：预加载+适配是任务真正动起来之前最长的一段等待，拆成「解码/适配」
        # 两段给出耗时，出问题时一眼能看出慢在读图还是慢在分辨率适配（悬浮球气泡只显示 info 级）
        self.log(f"成功预加载了 {len(self.cached_templates)} 张模板图片"
                 f"（解码 {decode_cost:.2f}s，适配 {elapsed - decode_cost:.2f}s）", 'info')
        # 返回成功加载的张数（脚本生成器里写的是 n = 图片预加载(...)，此前恒为 None）
        return len(self.cached_templates)

    def _match_by_orb(self, main_gray, img_name, temp_info, sim, offset_x=0, offset_y=0):
        '''
        使用 ORB 特征匹配（对尺寸变化更鲁棒）
        适用于缩放后尺寸过小的模板图
        '''
        template = temp_info['img']
        
        # 使用缓存的 ORB 检测器
        orb = self._get_orb_detector()
        
        # 检测关键点和描述符
        kp1, des1 = orb.detectAndCompute(template, None)
        kp2, des2 = orb.detectAndCompute(main_gray, None)
        
        if des1 is None or des2 is None or len(kp1) < 3 or len(kp2) < 10:
            if self.mode == "more":
                self.log(f"找图失败(ORB): {img_name} (关键点不足)", 'warning')
            return None
        
        # 使用缓存的 BFMatcher
        bf = self._get_bf_matcher()
        matches = bf.match(des1, des2)
        
        if len(matches) < 3:
            if self.mode == "more":
                self.log(f"找图失败(ORB): {img_name} (匹配点不足)", 'warning')
            return None
        
        # 按匹配距离排序
        matches = sorted(matches, key=lambda x: x.distance)
        
        # 计算匹配率（好匹配占比）
        # 距离阈值设为 40，允许更多匹配点
        good_matches = [m for m in matches if m.distance < 40]
        
        if len(good_matches) < 3:
            if self.mode == "more":
                self.log(f"找图失败(ORB): {img_name} (好匹配点不足)", 'warning')
            return None
        
        match_ratio = len(good_matches) / len(kp1)
        
        # 匹配率阈值：至少需要 sim*0.4 的匹配率
        if match_ratio < (sim * 0.4):
            if self.mode == "more":
                self.log(f"找图失败(ORB): {img_name} (匹配率:{match_ratio:.4f})", 'warning')
            return None
        
        # 获取匹配点坐标
        src_pts = np.float32([kp1[m.queryIdx].pt for m in good_matches]).reshape(-1, 1, 2)
        dst_pts = np.float32([kp2[m.trainIdx].pt for m in good_matches]).reshape(-1, 1, 2)
        
        # 使用 RANSAC 计算单应性矩阵
        try:
            M, mask = cv2.findHomography(src_pts, dst_pts, cv2.RANSAC, 5.0)
        except:
            if self.mode == "more":
                self.log(f"找图失败(ORB): {img_name} (单应性矩阵计算失败)", 'warning')
            return None
        
        if M is None:
            if self.mode == "more":
                self.log(f"找图失败(ORB): {img_name} (单应性矩阵为空)", 'warning')
            return None
        
        # 获取模板四个角在大图中的位置
        h, w = template.shape[:2]
        pts = np.float32([[0, 0], [0, h - 1], [w - 1, h - 1], [w - 1, 0]]).reshape(-1, 1, 2)
        dst = cv2.perspectiveTransform(pts, M)
        
        # 计算中心点
        dst = dst.reshape(4, 2)
        center_x = int(np.mean(dst[:, 0])) + offset_x
        center_y = int(np.mean(dst[:, 1])) + offset_y
        
        # 计算宽高
        w_resized = int(np.linalg.norm(dst[0] - dst[3]))
        h_resized = int(np.linalg.norm(dst[0] - dst[1]))
        
        # 计算匹配半径（使用优化的方法，小图偏移更小）
        min_side = min(w_resized, h_resized)
        r = self._calc_click_radius(min_side)
        
        if self.mode == "more":
            self.log(f"找图成功(ORB): {img_name} (匹配率:{match_ratio:.4f})", 'debug')
        
        # 返回匹配结果(中心点x、中心点y、内切圆半径、匹配图宽、匹配图高、匹配率)
        return img_name, (center_x, center_y, r, w_resized, h_resized, float(match_ratio))
    
    def _pick_best_location(self, result, threshold, priority_corner, w_main, h_main):
        """
        与既有逻辑一致：在匹配结果中挑选最优位置 (x, y)。
        规则：优先角优先度（越靠近指定角越好），同距离取匹配值更高的点。
        :return: (x, y, val) 或 None
        """
        locations = np.where(result >= threshold)
        best_dist = float('inf')
        best_loc = None
        best_val = 0

        for y, x in zip(*locations):
            val = result[y, x]
            if priority_corner == 'tl':
                dist = x + y
            elif priority_corner == 'tr':
                dist = (w_main - x) + y
            elif priority_corner == 'bl':
                dist = x + (h_main - y)
            elif priority_corner == 'br':
                dist = (w_main - x) + (h_main - y)
            else:
                dist = x + y

            if dist < best_dist or (dist == best_dist and val > best_val):
                best_dist = dist
                best_loc = (x, y)
                best_val = val

        if best_loc is None:
            return None
        return int(best_loc[0]), int(best_loc[1]), float(best_val)

    def _resize_to_target(self, template_img, tw, th):
        """按目标尺寸缩放模板：整体缩小用 INTER_AREA、放大用 INTER_CUBIC（插值选择与原逻辑一致）"""
        h, w = template_img.shape[:2]
        if tw == w and th == h:
            return template_img
        interp = cv2.INTER_AREA if (tw <= w and th <= h) else cv2.INTER_CUBIC
        return cv2.resize(template_img, (tw, th), interpolation=interp)

    def _match_roi(self, main_gray, template_img, tw, th, sim, offset_x, offset_y,
                   priority_corner, cx_local, cy_local):
        """
        在“上一帧命中中心”附近的小邻域内匹配（连续帧 UI 基本静止，开销远小于整屏扫描）。
        :param cx_local, cy_local: 上一帧中心在当前截图(局部)坐标下的位置
        :return: (center_x, center_y, score) 全屏绝对坐标；未命中返回 None
        """
        w_main, h_main = main_gray.shape[:2]
        if tw <= 0 or th <= 0 or tw > w_main or th > h_main:
            return None
        # 邻域半宽：覆盖模板自身 + 少量移动余量；取得越大越稳但越慢
        halfw = int(tw * 0.9) + 24
        halfh = int(th * 0.9) + 24
        x0 = max(0, cx_local - halfw)
        x1 = min(w_main, cx_local + halfw + 1)
        y0 = max(0, cy_local - halfh)
        y1 = min(h_main, cy_local + halfh + 1)
        if x1 - x0 < tw or y1 - y0 < th:
            return None
        roi = main_gray[y0:y1, x0:x1]
        resized = self._resize_to_target(template_img, tw, th)
        result = cv2.matchTemplate(roi, resized, cv2.TM_CCOEFF_NORMED)
        _, max_val, _, _ = cv2.minMaxLoc(result)
        if max_val < sim:
            return None
        best = self._pick_best_location(result, max_val, priority_corner, x1 - x0, y1 - y0)
        if best is None:
            return None
        bx, by = best[0], best[1]
        return int(bx + x0 + tw // 2) + offset_x, int(by + y0 + th // 2) + offset_y, float(max_val)

    def _match_scaled_full(self, main_gray, template_img, tw, th, sim, offset_x, offset_y, priority_corner):
        """
        整屏单次“把模板缩放到实际尺寸后匹配”。坐标直接落在原图上，无需先整屏缩放截图。
        :return: (center_x, center_y, score) 全屏绝对坐标；未命中返回 None
        """
        w_main, h_main = main_gray.shape[:2]
        if tw <= 0 or th <= 0 or tw > w_main or th > h_main:
            return None
        resized = self._resize_to_target(template_img, tw, th)
        result = cv2.matchTemplate(main_gray, resized, cv2.TM_CCOEFF_NORMED)
        _, max_val, _, _ = cv2.minMaxLoc(result)
        if max_val < sim:
            return None
        best = self._pick_best_location(result, max_val, priority_corner, w_main, h_main)
        if best is None:
            return None
        bx, by = best[0], best[1]
        return int(bx + tw // 2) + offset_x, int(by + th // 2) + offset_y, float(max_val)

    # 无基准分辨率信息时的默认基准（上传图、数组图、PIL 图都走这里）
    DEFAULT_BASE_RESOLUTION = (1920, 1080)

    # 「画面上没有这批模板」的判据（名义比例验证时的最高匹配分低于此值即认定）。
    # 阈值由实测定：模板确实在场、只是比例偏 ±15% 时最高分通常 ≥0.8；而画面根本
    # 没有该内容时，TM_CCOEFF_NORMED 的噪声峰值也能到 0.65（实测 0.6505），故取 0.75。
    NO_TEMPLATE_SCORE = 0.75

    # 「模板与画面朝向/比例根本不是一回事」的判据：名义缩放两个方向的比值超过此值，
    # 说明二者连纵横关系都不一致（典型：模板是 16:9 横屏截图，而设备此刻停在
    # 1080x2460 竖屏上，scale=(0.5625, 2.2778)，skew=4.05）。宽高比失配在全面屏上
    # 本来就常见（16:9 模板 vs 20.5:9 屏幕 → skew≈1.28），所以阈值放到 2.0，
    # 只拦「方向级」的离谱情况，保留对真实非均匀拉伸（小窗模式）的搜索能力。
    SKEW_SKIP_THRESHOLD = 2.0

    def _load_template(self, img, img_index, base_resolution=None):
        """
        解码单张模板为灰度图（供 图片预加载 并发调用）。
        :param base_resolution: 可选，(宽, 高)。仅当图片自身不带分辨率信息时（上传字节流、
            cv2 数组、PIL 对象、文件名无 _WxH 后缀）作为模板基准分辨率，用于算缩放比与匹配方向
        :return: (img_name, temp_info, warn_msg)；失败时 img_name 为 None，warn_msg 为提示语
        """
        gray_img = None
        screen_width = None
        screen_height = None
        img_name = None
        try:
            if isinstance(img, str):
                if not os.path.exists(img) and self.mode == "more":
                    return None, None, f"警告：图片文件不存在 - {img}"
                img_name = os.path.basename(img)
                # 从文件名提取屏幕参数（格式：xxx_WxH.png）
                screen_width, screen_height = self._parse_screen_from_filename(img_name)
                # 用 np.fromfile + cv2.imdecode 替代 cv2.imread
                # 解决 Windows 下 cv2.imread 不支持中文路径的问题
                img_data = np.fromfile(img, dtype=np.uint8)
                gray_img = cv2.imdecode(img_data, cv2.IMREAD_GRAYSCALE)
                if gray_img is None:
                    return None, None, f"警告：图片读取失败 - {img}"
            elif isinstance(img, bytes):
                img_name = f"uploaded_image_{img_index}"
                img_array = np.frombuffer(img, np.uint8)
                color_img = cv2.imdecode(img_array, cv2.IMREAD_COLOR)
                if color_img is not None:
                    gray_img = cv2.cvtColor(color_img, cv2.COLOR_BGR2GRAY)
                else:
                    return None, None, f"警告：字节图片解码失败 - {img_name}"
            elif isinstance(img, np.ndarray):
                img_name = f"cv2_array_{img_index}"
                if len(img.shape) == 2:
                    gray_img = img
                else:
                    gray_img = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
            elif hasattr(img, 'read'):
                img_name = img.filename or "uploaded_image"
                # 上传接口会把来源设备分辨率拼进文件名（xxx_WxH.png），这里一并解析，
                # 让上传的模板以自己的分辨率为基准，而不是一律套用默认基准
                screen_width, screen_height = self._parse_screen_from_filename(img_name)
                img_bytes = img.read()
                img_array = np.frombuffer(img_bytes, np.uint8)
                color_img = cv2.imdecode(img_array, cv2.IMREAD_COLOR)
                if color_img is not None:
                    gray_img = cv2.cvtColor(color_img, cv2.COLOR_BGR2GRAY)
                else:
                    return None, None, f"警告：上传图片解码失败 - {img_name}"
            elif hasattr(img, 'tobytes'):
                img_name = f"pil_image_{img_index}"
                img_array = np.array(img)
                if len(img_array.shape) == 2:
                    gray_img = img_array
                elif img_array.shape[2] == 4:
                    gray_img = cv2.cvtColor(img_array, cv2.COLOR_RGBA2GRAY)
                else:
                    gray_img = cv2.cvtColor(img_array, cv2.COLOR_RGB2GRAY)
            else:
                return None, None, f"警告：不支持的图片类型 - {type(img)}"
        except Exception as e:
            return None, None, f"警告：加载图片失败 - {img if isinstance(img, str) else type(img)}: {e}"

        # 图片自身不带分辨率信息时（上传字节流/数组图/PIL 图/文件名无 _WxH 后缀），
        # 用调用方声明的来源分辨率兜底；否则会退回 DEFAULT_BASE_RESOLUTION(1920x1080)，
        # 在非 1920 设备上缩放比算错，方向判据（截图分辨率 vs 模板基准）也跟着走错分支。
        if base_resolution and (screen_width is None or screen_height is None):
            try:
                screen_width, screen_height = int(base_resolution[0]), int(base_resolution[1])
            except (TypeError, ValueError, IndexError):
                pass

        source_path = None
        if isinstance(img, str):
            source_path = img
        elif hasattr(img, 'filename') and img.filename:
            source_path = img.filename
        elif hasattr(img, 'name') and isinstance(img.name, str):
            source_path = img.name

        temp_info = {
            'img': gray_img,
            'h': gray_img.shape[0],
            'w': gray_img.shape[1],
            'screen_width': screen_width,
            'screen_height': screen_height,
            'source_path': source_path,
            # 名义缩放比 = 截图尺寸 / 该图自身基准分辨率；实际缩放比 = 名义比 × 修正因子
            # 两者都在 _compute_template_scales 里统一填充（每张模板各算各的）
            'scale_nominal_x': None,
            'scale_nominal_y': None,
            'scale_x': None,
            'scale_y': None,
        }
        return img_name, temp_info, None

    def _compute_template_scales(self, screenshot_w, screenshot_h,
                                 correction_x=1.0, correction_y=1.0):
        """
        为每张模板按“自身文件名里的基准分辨率”计算缩放比。
        彻底解决旧逻辑“只取第一张模板的分辨率当全库基准”导致其它基准的模板被错误缩放的问题。
        无基准信息的模板（上传图/数组图）回退到默认基准 1920x1080。
        顺带刷新全局参考比例（参考组 = 模板数量最多的基准组），供旧接口与无信息模板兜底。
        :return: 参与计算的模板数
        """
        base_w, base_h = self.DEFAULT_BASE_RESOLUTION
        groups = {}
        for img_name, temp in self.cached_templates.items():
            bw = temp.get('screen_width') or base_w
            bh = temp.get('screen_height') or base_h
            temp['scale_nominal_x'] = screenshot_w / bw
            temp['scale_nominal_y'] = screenshot_h / bh
            temp['scale_x'] = temp['scale_nominal_x'] * correction_x
            temp['scale_y'] = temp['scale_nominal_y'] * correction_y
            groups[(bw, bh)] = groups.get((bw, bh), 0) + 1

        if not self.cached_templates:
            return 0

        # 参考组：模板数量最多的基准分辨率（并列时取先出现的），其缩放比写入 current_scale*
        ref = max(groups.items(), key=lambda kv: kv[1])[0]
        for temp in self.cached_templates.values():
            if (temp.get('screen_width') or base_w,
                    temp.get('screen_height') or base_h) == ref:
                self.current_scale_x = temp['scale_x']
                self.current_scale_y = temp['scale_y']
                self.current_scale = (temp['scale_x'] + temp['scale_y']) / 2
                break

        if self.mode == "more":
            group_desc = ", ".join(f"{w}x{h}:{c}张" for (w, h), c in groups.items())
            self.log(f"基准分辨率分组 [{group_desc}]；参考组 {ref[0]}x{ref[1]} "
                     f"scale=({self.current_scale_x:.4f},{self.current_scale_y:.4f})", 'debug')
        return len(self.cached_templates)

    def _verify_templates(self, sim, test_images, main_gray, w_main, h_main,
                          correction_x=1.0, correction_y=1.0, record=True):
        """
        按“每张模板自身名义缩放比 × 修正因子”整屏验证一批模板。
        一次遍历同时完成两件事：
          1) 统计通过情况，供“当前比例是否可用”的决策（避免旧实现先全量验证、再全量记录算两遍）；
          2) 把 ≥sim 的模板实测尺寸写入 _find_cache（validated），供找图阶段走快速路径。
        :return: (success, total, total_score, max_score)
            max_score = 本批模板中的**最高**匹配分（无论过没过阈值）。调用方靠它区分
            「验证失败」的两种截然不同的成因：
              · max_score 很低 → 画面上压根没有这批模板（预加载时最常见：模板所在
                界面还没打开），此时搜修正因子只是在噪声上挑最优，纯浪费；
              · max_score 接近阈值 → 模板在场、只是比例略偏，这才值得搜修正因子。
        """
        success = 0
        total = 0
        total_score = 0.0
        max_score = 0.0
        base_w, base_h = self.DEFAULT_BASE_RESOLUTION
        for img_name in test_images:
            temp = self.cached_templates.get(img_name)
            if not temp or temp['w'] < 20 or temp['h'] < 20:
                continue
            nominal_x = temp.get('scale_nominal_x')
            nominal_y = temp.get('scale_nominal_y')
            if nominal_x is None or nominal_y is None:
                bw = temp.get('screen_width') or base_w
                bh = temp.get('screen_height') or base_h
                nominal_x, nominal_y = w_main / bw, h_main / bh
            tw = int(temp['w'] * nominal_x * correction_x)
            th = int(temp['h'] * nominal_y * correction_y)
            if tw > w_main or th > h_main or tw < 10 or th < 10:
                continue
            total += 1
            resized = self._resize_to_target(temp['img'], tw, th)
            result = cv2.matchTemplate(main_gray, resized, cv2.TM_CCOEFF_NORMED)
            _, max_val, _, _ = cv2.minMaxLoc(result)
            if max_val > max_score:
                max_score = max_val
            if max_val >= sim:
                success += 1
                total_score += max_val
                if record:
                    self._find_cache[img_name] = {'x': None, 'y': None, 'w': tw, 'h': th,
                                                  'validated': True, 'miss': 0}
        return success, total, total_score, max_score

    def _sample_images(self, images, limit=5):
        """
        跨基准分辨率分组均匀采样：保证每个基准组都有代表参与修正因子搜索，
        避免旧实现只在一批同基准的图上验证、漏掉其它基准。大图优先（小图匹配噪声大）。
        :return: 采样出的模板名列表（长度 ≤ limit）
        """
        base_w, base_h = self.DEFAULT_BASE_RESOLUTION
        buckets = {}
        for img_name in images:
            temp = self.cached_templates.get(img_name)
            if not temp or temp['w'] < 20 or temp['h'] < 20:
                continue
            key = (temp.get('screen_width') or base_w, temp.get('screen_height') or base_h)
            buckets.setdefault(key, []).append((temp['w'] * temp['h'], img_name))
        for key in buckets:
            buckets[key].sort(reverse=True)  # 面积大的优先

        picked = []
        idx = 0
        while len(picked) < limit:
            added = False
            for key, items in buckets.items():
                if idx < len(items) and len(picked) < limit:
                    picked.append(items[idx][1])
                    added = True
            if not added:
                break
            idx += 1
        return picked

    def _match_single_task(self, main_gray, img_name, sim, offset_x=0, offset_y=0, priority_corner='tl', pool=None, hint=None):
        '''
        多线程内部执行的单张图匹配任务
        :param offset_x, offset_y: 局部截图相对于全屏的偏移量
        :param priority_corner: 角优先度，可选 'tl', 'tr', 'bl', 'br'，默认左上角tl
        :param pool: 共享的整屏缩放候选池（同一次找图中所有模板复用同一批缩放截图，
                     传入 None 时退化为只做原始尺寸直接匹配）
        :param hint: 该模板上一帧的命中缓存 {x, y, w, h, validated}；x/y 为全屏绝对坐标
        '''
        temp_info = self.cached_templates.get(img_name)
        if not temp_info:
            return None
        h_main, w_main = main_gray.shape[:2]

        # 移除小图特殊处理，让所有尺寸的图片都参与正常的模板匹配流程
        # 小图（如 164x37 的二星图标）也能通过模板匹配找到，不需要特殊处理
        # if temp_info['w'] < 50 or temp_info['h'] < 50:
        #     ...

        # 每张模板用自己的缩放比（自身基准分辨率 × 全局修正因子），取不到才回退参考组比例
        base_scale_x = temp_info.get('scale_x')
        base_scale_y = temp_info.get('scale_y')
        if base_scale_x is None or base_scale_y is None:
            base_scale_x = getattr(self, 'current_scale_x', self.current_scale)
            base_scale_y = getattr(self, 'current_scale_y', self.current_scale)
        if base_scale_x is None or base_scale_y is None:
            raise TaskStoppedException("未锁定分辨率，请先调用'适配分辨率(adapt_res_everytime)'方法进行测算！")
        
        # 计算平均缩放因子
        avg_scale = (base_scale_x + base_scale_y) / 2

        native_w = temp_info['w']
        native_h = temp_info['h']

        # 方向判据：按「匹配用的截图分辨率」和「模板图片自身的基准分辨率」的大小关系选匹配方式
        # （avg_scale = 截图尺寸 / 该模板基准尺寸）
        #   >= 1：截图分辨率比图片大 → 阶段2：把模板缩放到实际尺寸，在原始截图上整屏匹配；
        #   <  1：截图分辨率比图片小 → 阶段3：把截图放大到基准分辨率，用原始模板整屏匹配。
        upscale_template = avg_scale >= 1.0

        # 目标在当前截图中的实测尺寸：优先取验证/命中记录（预加载阶段写入），没有才按名义缩放比推算
        if hint is not None and hint.get('w') and hint.get('h'):
            tw_measure, th_measure = int(hint['w']), int(hint['h'])
        else:
            tw_measure = int(round(native_w * base_scale_x))
            th_measure = int(round(native_h * base_scale_y))

        # ========== 阶段1：局部邻域优先（连续帧静止 UI：命中时开销只有整屏的百分之几） ==========
        # 复用上一帧命中的位置与实测尺寸，只在上次中心附近的小区域里匹配
        if (hint is not None and hint.get('x') is not None and hint.get('y') is not None
                and hint.get('w') and hint.get('h')):
            hw, hh = int(hint['w']), int(hint['h'])
            cx_local = int(hint['x']) - offset_x
            cy_local = int(hint['y']) - offset_y
            attempts = [(hw, hh)]
            # 再补一次“原始模板尺寸”的尝试，覆盖控件不随分辨率缩放（固定像素）的情况
            if (native_w, native_h) != (hw, hh):
                attempts.append((native_w, native_h))
            for tw, th in attempts:
                if tw < 10 or th < 10:
                    continue
                hit = self._match_roi(main_gray, temp_info['img'], tw, th, sim,
                                      offset_x, offset_y, priority_corner, cx_local, cy_local)
                if hit is not None:
                    cx, cy, score = hit
                    if self.mode == "more":
                        self.log(f"找图成功(局部优先): {img_name} (匹配度:{score:.4f}, 尺寸:{tw}x{th})", 'debug')
                    min_side = min(tw, th)
                    r = self._calc_click_radius(min_side)
                    return img_name, (cx, cy, r, tw, th, float(score))

        # ========== 阶段2：截图分辨率 >= 模板基准分辨率 → 把模板缩放到实际尺寸后整屏匹配 ==========
        # 截图比模板图大时，目标在截图里更清晰：放大模板（INTER_CUBIC）后再整屏匹配，
        # 比“把截图缩小到基准分辨率”保留更多细节，实测相似度也更高（0.998+ vs 0.975）。
        if upscale_template:
            hit = self._match_scaled_full(main_gray, temp_info['img'], tw_measure, th_measure, sim,
                                          offset_x, offset_y, priority_corner)
            if hit is not None:
                cx, cy, score = hit
                if self.mode == "more":
                    self.log(f"找图成功(阶段2·模板适配尺寸整屏): {img_name} "
                             f"(匹配度:{score:.4f}, 尺寸:{tw_measure}x{th_measure})", 'debug')
                min_side = min(tw_measure, th_measure)
                r = self._calc_click_radius(min_side)
                return img_name, (cx, cy, r, tw_measure, th_measure, float(score))
            # 该尺寸已在预加载/上一帧以 ≥sim 验证通过 → 当前帧确实没有该目标，
            # 不必再走“原始尺寸 + ±5% 网格”那套较重兜底（这是记录分辨率提速的关键）
            if hint is not None and hint.get('validated'):
                return None

        # ========== 阶段3：截图分辨率 < 模板基准分辨率 的主路径（scale >= 1 时仅作抗比例偏差的兜底） ==========
        # 3a) 目标可能与模板同像素（不随分辨率缩放的固定像素 UI）→ 用原始模板尺寸直接搜一次；
        # 3b) pool：把整屏截图缩放到各自基准分辨率，再用原始模板匹配；scale < 1 时这一步即“放大截图”。

        # ========== 优化策略：先尝试原始尺寸直接匹配 ==========
        # 直接用原始模板尺寸在大图中搜索，避免缩放带来的撕裂和精度损失
        # 这是解决"大图放大小图匹配度低"问题的关键改进
        # 原理：模板是基准分辨率(1920x1080)的，当截图分辨率 > 基准分辨率时（scale_factor >= 1），
        # 截图中目标的实际尺寸 = 模板尺寸 * scale_factor，
        # 直接用原始模板匹配相当于在大图中搜索模板大小的区域，匹配到的位置是模板左上角在截图中的坐标
        # 但实际目标尺寸是模板尺寸 * scale_factor，所以需要用实际尺寸计算中心坐标
        # 注意：只有当 scale_factor >= 1 时才适用（截图分辨率 >= 基准分辨率），
        # 当 scale_factor < 1 时，模板尺寸 > 截图中目标尺寸，直接匹配会出错
        direct_max_val = 0.0
        direct_result = None
        
        # 只有当 scale_factor >= 1 时才尝试原始尺寸直接匹配
        if avg_scale >= 1.0 and temp_info['w'] <= w_main and temp_info['h'] <= h_main:
            direct_result = cv2.matchTemplate(main_gray, temp_info['img'], cv2.TM_CCOEFF_NORMED)
            _, direct_max_val, _, _ = cv2.minMaxLoc(direct_result)
        
        # 如果原始尺寸匹配度已经很高，直接返回（避免缩放带来的精度损失）
        # 关键修正：匹配坐标是模板左上角在截图中的位置，
        # 但实际目标尺寸是模板尺寸 * 缩放因子，所以需要用实际尺寸计算中心坐标
        if direct_result is not None and direct_max_val >= sim:
            if self.mode == "more":
                self.log(f"找图成功(原始尺寸): {img_name} (匹配度:{direct_max_val:.4f})", 'debug')
            
            # 使用实际匹配度作为阈值，确保选择匹配度最高的位置
            locations = np.where(direct_result >= direct_max_val)
            best_dist = float('inf')
            best_loc = None
            best_val = 0
            
            for y, x in zip(*locations):
                val = direct_result[y, x]
                if priority_corner == 'tl':
                    dist = x + y
                elif priority_corner == 'tr':
                    dist = (w_main - x) + y
                elif priority_corner == 'bl':
                    dist = x + (h_main - y)
                elif priority_corner == 'br':
                    dist = (w_main - x) + (h_main - y)
                else:
                    dist = x + y
                
                if dist < best_dist or (dist == best_dist and val > best_val):
                    best_dist = dist
                    best_loc = (x, y)
                    best_val = val
            
            if best_loc:
                match_x, match_y = best_loc
                # 计算实际目标尺寸（模板尺寸 * 缩放因子）
                actual_w = int(temp_info['w'] * base_scale_x)
                actual_h = int(temp_info['h'] * base_scale_y)
                
                # 中心坐标 = 匹配位置 + 实际尺寸的一半 + 偏移量
                center_x = int(match_x + actual_w // 2) + offset_x
                center_y = int(match_y + actual_h // 2) + offset_y
                
                # 计算匹配半径（使用优化的方法，小图偏移更小）
                min_side = min(actual_w, actual_h)
                r = self._calc_click_radius(min_side)
                
                return img_name, (center_x, center_y, r, actual_w, actual_h, float(best_val))
        
        # ========== 智能双向缩放匹配（作为 fallback） ==========
        # 缩放整屏截图开销大，且同一帧所有模板共享同一组缩放参数；
        # 通过 pool 让缩放只按需发生一次并全体复用，避免 N 张模板重复缩放整屏。
        # 候选0 为「精确档」（按当前缩放比），候选1.. 为 ±0.05 微调网格，
        # 尺寸/插值/映射系数与原逻辑逐模板计算时完全一致，因此匹配数值不变。
        best_match = None
        best_match_val = max(direct_max_val, 0.0)

        if pool is not None:
            entries = pool.entries

            def _try_candidate(idx):
                nonlocal best_match, best_match_val
                dw, dh, fx, fy = entries[idx]
                if dw < temp_info['w'] or dh < temp_info['h']:
                    return
                if dw == w_main and dh == h_main:
                    # 与原始截图同尺寸：结果恒等于上面已算过的直接匹配，无需重算
                    return
                resized = pool.image(idx)
                result = cv2.matchTemplate(resized, temp_info['img'], cv2.TM_CCOEFF_NORMED)
                _, max_val, _, _ = cv2.minMaxLoc(result)
                if max_val > best_match_val:
                    best_match_val = max_val
                    best_match = (pool.kind, result, temp_info['w'], temp_info['h'], dw, dh, fx, fy)

            if entries:
                _try_candidate(0)
            # 精确档仍不够好时（<0.85）才搜索微调网格，与原逻辑一致
            if best_match_val < 0.85:
                for idx in range(1, len(entries)):
                    check_stop(self)
                    _try_candidate(idx)

        # ========== 最终比较：选择最佳匹配结果 ==========
        if best_match is not None and best_match_val >= sim:
            match_type, result, w_resized, h_resized, w_main_res, h_main_res, scale_x, scale_y = best_match
            if self.mode == "more":
                if match_type == 'scale_down':
                    self.log(f"找图成功(缩放-缩小截图): {img_name} (匹配度:{best_match_val:.4f})")
                elif match_type == 'scale_up':
                    self.log(f"找图成功(缩放-放大截图): {img_name} (匹配度:{best_match_val:.4f})")
                else:
                    self.log(f"找图成功(缩放-缩小模板): {img_name} (匹配度:{best_match_val:.4f})")

            # 使用实际匹配度作为阈值，确保选择匹配度最高的位置，而不是角优先度最高的位置
            return self._select_best_location_with_scale(
                result, w_resized, h_resized, best_match_val,
                priority_corner, w_main_res, h_main_res, offset_x, offset_y, img_name,
                scale_x, scale_y
            )

        # ========== 阶段3 兜底：scale < 1 时的保险（主路径是上面把截图放大到基准分辨率） ==========
        # 该路径依赖 pool；分辨率刚变化、pool 未构建等情况下会比主路径少一次尝试，
        # 这里退回“把模板缩放到实际尺寸后再匹配”补一次，避免整条链路一次都没试就漏检。
        # （主路径仍是阶段3：只有它没命中时才会走到这里）
        if not upscale_template:
            hit = self._match_scaled_full(main_gray, temp_info['img'], tw_measure, th_measure, sim,
                                          offset_x, offset_y, priority_corner)
            if hit is not None:
                cx, cy, score = hit
                if self.mode == "more":
                    self.log(f"找图成功(阶段3兜底·模板适配尺寸整屏): {img_name} "
                             f"(匹配度:{score:.4f}, 尺寸:{tw_measure}x{th_measure})", 'debug')
                min_side = min(tw_measure, th_measure)
                r = self._calc_click_radius(min_side)
                return img_name, (cx, cy, r, tw_measure, th_measure, float(score))

        if self.mode == "more":
            self.log(f"找图失败: {img_name} (原始尺寸匹配度:{direct_max_val:.4f}, 缩放匹配度:{best_match_val:.4f})", 'warning')
        return None
    
    def _select_best_location(self, result, w_resized, h_resized, threshold, 
                             priority_corner, w_main, h_main, offset_x, offset_y, img_name):
        """
        根据角优先度选择最佳匹配位置
        """
        locations = np.where(result >= threshold)
        
        best_dist = float('inf')
        best_loc = None
        best_val = 0
        
        for y, x in zip(*locations):
            val = result[y, x]
            if priority_corner == 'tl':
                dist = x + y
            elif priority_corner == 'tr':
                dist = (w_main - x) + y
            elif priority_corner == 'bl':
                dist = x + (h_main - y)
            elif priority_corner == 'br':
                dist = (w_main - x) + (h_main - y)
            else:
                dist = x + y
            
            if dist < best_dist or (dist == best_dist and val > best_val):
                best_dist = dist
                best_loc = (x, y)
                best_val = val
        
        if best_loc:
            x, y = best_loc
            center_x = int(x + w_resized // 2) + offset_x
            center_y = int(y + h_resized // 2) + offset_y
            
            min_side = min(w_resized, h_resized)
            r = self._calc_click_radius(min_side)
            return img_name, (center_x, center_y, r, w_resized, h_resized, float(best_val))
        
        return None
    
    def _select_best_location_with_scale(self, result, w_resized, h_resized, threshold,
                                         priority_corner, w_main, h_main, offset_x, offset_y, img_name,
                                         scale_down_x, scale_down_y):
        """
        根据角优先度选择最佳匹配位置（带缩放因子，用于缩小截图后匹配的坐标反向映射）
        :param scale_down_x, scale_down_y: 截图缩小的比例，用于反向映射回原始坐标
        """
        locations = np.where(result >= threshold)
        
        best_dist = float('inf')
        best_loc = None
        best_val = 0
        
        for y, x in zip(*locations):
            val = result[y, x]
            if priority_corner == 'tl':
                dist = x + y
            elif priority_corner == 'tr':
                dist = (w_main - x) + y
            elif priority_corner == 'bl':
                dist = x + (h_main - y)
            elif priority_corner == 'br':
                dist = (w_main - x) + (h_main - y)
            else:
                dist = x + y
            
            if dist < best_dist or (dist == best_dist and val > best_val):
                best_dist = dist
                best_loc = (x, y)
                best_val = val
        
        if best_loc:
            x, y = best_loc
            # 坐标反向映射回原始截图：除以缩小比例
            center_x = int((x + w_resized // 2) / scale_down_x) + offset_x
            center_y = int((y + h_resized // 2) / scale_down_y) + offset_y
            
            # 计算原始尺寸下的匹配半径（使用优化的方法，小图偏移更小）
            orig_w = int(w_resized / scale_down_x)
            orig_h = int(h_resized / scale_down_y)
            min_side = min(orig_w, orig_h)
            r = self._calc_click_radius(min_side)
            return img_name, (center_x, center_y, r, orig_w, orig_h, float(best_val))
        
        return None
    

    def _adapt_resolution(self, sim=0.90, target_image=None):
        '''
        测算分辨率缩放比。与旧实现的关键区别：
          1. 每张模板按“自身文件名里的基准分辨率”独立换算缩放比（不再全库共用第一张的比例），
             混用不同基准分辨率的模板库也能各自正确匹配；
          2. 实测微调以“全局修正因子”的形式叠加到所有模板——设备渲染相对等比缩放的偏差
             是无量纲的比例量，所以在采样集上搜一次即可，不必每张图各搜一遍；
          3. 验证与“实测尺寸记录”合并为一次遍历，不再把同一批整屏匹配算两遍；
          4. 修正因子搜索改为跨分组采样（≤5 张），最坏情况匹配次数从约 11N 降到约 N+8S。
        :param sim: 匹配阈值，默认 0.90
        :param target_image: 指定图片，若为 None 则验证库中所有图。
        '''
        if not self.cached_templates:
            raise TaskStoppedException("缓存的模板库为空，请先调用'图片预加载'方法加载！")

        main_img = self.获取截图()
        main_gray = cv2.cvtColor(main_img, cv2.COLOR_BGR2GRAY)
        h_main, w_main = main_gray.shape[:2]
        screenshot_w, screenshot_h = w_main, h_main

        if self.mode == "more":
            self.log(f"截图分辨率: {screenshot_w}x{screenshot_h}", 'debug')

        # 1) 每张模板按自身基准分辨率算名义缩放比（修正因子先按 1.0 代入）
        self._compute_template_scales(screenshot_w, screenshot_h)

        test_images = [target_image] if target_image else list(self.cached_templates.keys())

        # 2) 名义比例验证 + 全量记录实测尺寸（一次遍历同时完成两件事）
        success_count, total_count, total_score, max_score = self._verify_templates(
            sim, test_images, main_gray, w_main, h_main, record=True)
        if total_count == 0:
            if self.mode == "more":
                self.log("没有可用于适配验证的模板（尺寸过小或全部超出画面）", 'warning')
            self._scale_correction = (1.0, 1.0)
            return True

        if success_count >= total_count * 0.5:
            avg_score = total_score / success_count if success_count > 0 else 0
            if self.mode == "more":
                self.log(f"名义比例验证通过: {success_count}/{total_count} 张匹配成功, "
                         f"平均相似度={avg_score:.4f}", 'debug')
            self._scale_correction = (1.0, 1.0)
            return True

        # 走到这里说明名义比例验证没过。但「没过」有两种完全不同的原因，修正因子搜索
        # 只对其中一种有意义，另一种纯属烧算力（代价是「≤5 张采样 × 14 组」次全屏
        # matchTemplate，手机上实测几十秒 —— 正是「任务启动要等一分钟」的主因）：

        # 原因一：模板与画面朝向/比例根本不是一回事（如竖屏时预加载横屏游戏模板）。
        # 修正因子只能在「等比」或「单独 y」两个方向做 ±15% 微调，覆盖不了这种差异。
        ref = next((t for t in self.cached_templates.values()
                    if t.get('scale_nominal_x') and t.get('scale_nominal_y')), None)
        if ref:
            sx, sy = ref['scale_nominal_x'], ref['scale_nominal_y']
            skew = max(sx / sy, sy / sx) if sx > 0 and sy > 0 else 1.0
            if skew >= self.SKEW_SKIP_THRESHOLD:
                if self.mode == "more":
                    self.log(f"模板与画面比例相差 {skew:.2f} 倍（scale={sx:.4f},{sy:.4f}），"
                             f"修正因子覆盖不到，按名义比例直接使用", 'debug')
                self._scale_correction = (1.0, 1.0)
                return True

        # 原因二：画面上根本没有这批模板（预加载时最常见——模板所在界面还没打开）。
        # 此时搜索只是在噪声里挑最大值：实测连"答案"都是 0 命中。
        if max_score < self.NO_TEMPLATE_SCORE:
            if self.mode == "more":
                self.log(f"当前画面未见模板（最高分 {max_score:.3f}），按名义比例直接使用，"
                         f"跳过修正因子搜索", 'debug')
            self._scale_correction = (1.0, 1.0)
            return True

        if self.mode == "more":
            self.log("名义比例验证失败，搜索全局修正因子...", 'debug')

        # 3) 跨分组采样，搜索全局修正因子
        # 采样降到 3 张：命中一组的判定只要"有图过阈值"，3 张足以反映比例是否偏，
        # 而代价从每组 5 次全屏匹配降到 3 次（最坏 14 组时省下 28 次）
        sample = self._sample_images(test_images, limit=3)
        if not sample:
            sample = test_images[:3]

        def _eval(cx, cy):
            """在采样集上评估一组修正因子，返回 (成功张数, 累计相似度)"""
            s = 0
            sc = 0.0
            for img_name in sample:
                temp = self.cached_templates.get(img_name)
                if not temp:
                    continue
                tw = int(temp['w'] * (temp['scale_nominal_x'] or 1.0) * cx)
                th = int(temp['h'] * (temp['scale_nominal_y'] or 1.0) * cy)
                if tw > w_main or th > h_main or tw < 10 or th < 10:
                    continue
                resized = self._resize_to_target(temp['img'], tw, th)
                result = cv2.matchTemplate(main_gray, resized, cv2.TM_CCOEFF_NORMED)
                _, max_val, _, _ = cv2.minMaxLoc(result)
                if max_val >= sim:
                    s += 1
                    sc += max_val
            return s, sc

        # 与旧网格同量级但覆盖更宽：±15%，步长 5%（旧为 ±5%、步长 3%）
        search_range = np.arange(-0.15, 0.151, 0.05)
        best_cx, best_cy = 1.0, 1.0
        best_success, best_score = _eval(1.0, 1.0)

        # 先搜等比修正（宽高同倍率，对应“整体缩放比估偏”）
        for dx in search_range:
            check_stop(self)
            cx = 1.0 + dx
            if cx <= 0.5 or cx >= 1.5:
                continue
            current_success, current_score = _eval(cx, cx)
            if current_success > best_success or \
               (current_success == best_success and current_score > best_score):
                best_success, best_score = current_success, current_score
                best_cx, best_cy = cx, cx

        # 等比没找到更好的，再单独搜 y 方向（对应非均匀拉伸，如小窗模式）
        if best_cx == 1.0:
            for dy in search_range:
                check_stop(self)
                cy = 1.0 + dy
                if cy <= 0.5 or cy >= 1.5:
                    continue
                current_success, current_score = _eval(1.0, cy)
                if current_success > best_success or \
                   (current_success == best_success and current_score > best_score):
                    best_success, best_score = current_success, current_score
                    best_cy = cy

        self._scale_correction = (best_cx, best_cy)

        # 4) 应用修正因子重算每张模板的缩放比，并重新记录实测尺寸
        self._compute_template_scales(screenshot_w, screenshot_h, best_cx, best_cy)
        success_count, total_count, total_score, _max_score = self._verify_templates(
            sim, test_images, main_gray, w_main, h_main,
            correction_x=best_cx, correction_y=best_cy, record=True)

        if self.mode == "more":
            avg_score = total_score / success_count if success_count else 0
            self.log(f"修正完成: correction=({best_cx:.2f},{best_cy:.2f}), "
                     f"{success_count}/{total_count} 张匹配成功, 平均相似度={avg_score:.4f}", 'debug')
        return True

    def 找图(self, sim=0.90, priority_corner='tl', x1: float=0, y1: float=0, x2: float=1.0, y2: float=1.0) -> dict[str, tuple]:
        '''
        :param sim: 匹配阈值，默认 0.90
        :param priority_corner: 角优先度，可选 'tl', 'tr', 'bl', 'br'，默认左上角tl
        :param x1, y1, x2, y2: 截图区域坐标，默认为全屏，范围 0~1.0
        返回值字典： {图名: (匹配坐标x, 匹配坐标y, 推荐点击半径r, 模板宽度, 模板高度, 匹配度)}
        '''
        check_timeout(self.device_id)

        if not self.cached_templates:
            raise TaskStoppedException("没有可用的模板图片，请先调用'图片预加载'方法加载图片！")

        # -1 / 像素值等越界写法统一成比例边界，避免算出天文数字的坐标偏移
        x1, y1, x2, y2 = self._归一化区域(x1, y1, x2, y2)
        # 区域无效时（如 x1 >= x2）获取截图会退化成整屏，偏移量必须同步归零，
        # 否则裁剪用的是全屏、坐标却按无效区域偏移，两者不一致导致返回坐标错位
        if x2 <= x1 or y2 <= y1:
            x1, y1, x2, y2 = 0.0, 0.0, 1.0, 1.0

        # 计算偏移量（局部截图相对于全屏的坐标偏移）
        offset_x = int(self.width * x1)
        offset_y = int(self.height * y1)
            
        # 已有分辨率，多线程并发极速找所有图
        output = {}
        img_names = list(self.cached_templates.keys())
        
        main_img = self.获取截图(x1, y1, x2, y2)
        main_gray = cv2.cvtColor(main_img, cv2.COLOR_BGR2GRAY) # type: ignore

        # 复用 init 里的线程池，避免 while 循环高频创建线程导致内存泄漏和 CPU 暴涨
        base_scale_x = getattr(self, 'current_scale_x', self.current_scale)
        base_scale_y = getattr(self, 'current_scale_y', self.current_scale)
        # 缩放候选池按“基准分辨率分组”构建：同组模板共用一批缩放截图（通常只有一组，开销与旧实现相同）；
        # 混用不同基准分辨率的模板库会各自成组、互不干扰，彻底消除“第一张决定全库比例”的隐患。
        # 坐标映射用组内比例，因此与旧实现逐模板计算的结果一致。
        default_base = self.DEFAULT_BASE_RESOLUTION

        def _base_key(name):
            temp = self.cached_templates.get(name)
            if not temp:
                return default_base
            return (temp.get('screen_width') or default_base[0],
                    temp.get('screen_height') or default_base[1])

        pools = {}
        for name in img_names:
            key = _base_key(name)
            if key in pools:
                continue
            temp = self.cached_templates.get(name) or {}
            sx = temp.get('scale_x') or base_scale_x
            sy = temp.get('scale_y') or base_scale_y
            # 缩放比缺失（分辨率刚变化、尚未重新适配）时该组池为 None，交由匹配线程抛原有异常提示
            if sx is not None and sy is not None:
                pools[key] = _ScaledMainPool(main_gray, sx, sy)

        # 命中缓存快照：交给匹配线程做“局部优先”，只读不改（写回在主线程统一做）
        hints = {name: self._find_cache.get(name) for name in img_names}

        def _task(name):
            return self._match_single_task(main_gray, name, sim, offset_x, offset_y,
                                           priority_corner, pools.get(_base_key(name)),
                                           hints.get(name))

        results = list(self.executor.map(_task, img_names))

        for result in results:
            if result:
                img_name, value = result
                output[img_name] = value
                save_path = self._save_match_crop(main_img, img_name, value)
                if save_path:
                    self.log(f"已保存匹配截图: {save_path}", 'info')
                # 记录命中：全屏绝对坐标 + 实测屏幕尺寸 + 已核验标记，供下一帧局部优先搜索
                self._find_cache[img_name] = {
                    'x': value[0], 'y': value[1],
                    'w': value[3], 'h': value[4],
                    'validated': True, 'miss': 0
                }
        # 连续多帧都未再命中的，清理缓存，避免旧位置长期残留引发无谓的局部尝试
        for name in img_names:
            entry = self._find_cache.get(name)
            if entry is not None and name not in output:
                entry['miss'] = entry.get('miss', 0) + 1
                if entry['miss'] >= 3:
                    del self._find_cache[name]
        if output:
            self.log('找图：'+str(output), 'debug')
        else:
            print(f"[DEBUG] [{self.device_id}_找图] 未匹配到任何图片")
        return output
    
    def 找字(self, x1: float = 0, y1: float = 0, x2: float = 1.0, y2: float = 1.0, Specified_image=None, target_txt: str = '', use_regex: bool = False):
        '''
        x1, y1, x2, y2: 截图区域坐标，默认为全屏， 范围 0~1.0
        Specified_image: 指定图片（如果不提供则使用当前截图）
        target_txt: 目标文本（如果不提供则返回所有文本框信息），支持正则表达式，返回值为匹配到的文本对应坐标或None
        use_regex: 是否启用正则匹配，默认为 False
        '''
        check_timeout(self.device_id)

        x1, y1, x2, y2 = self._归一化区域(x1, y1, x2, y2)
        # 区域无效时 获取截图 会退化成整屏，偏移量必须同步归零（理由同 找图）
        if x2 <= x1 or y2 <= y1:
            x1, y1, x2, y2 = 0.0, 0.0, 1.0, 1.0

        if Specified_image:
            if isinstance(Specified_image, bytes):
                img_array = np.frombuffer(Specified_image, np.uint8)
                crop_img = cv2.imdecode(img_array, cv2.IMREAD_COLOR)
            elif isinstance(Specified_image, np.ndarray):
                crop_img = Specified_image
            elif hasattr(Specified_image, 'tobytes'):
                crop_img = cv2.cvtColor(np.array(Specified_image), cv2.COLOR_RGB2BGR)
            else:
                crop_img = Specified_image
        else:
            crop_img = self.获取截图(x1, y1, x2, y2)
        result = self.engine(crop_img, use_det=True, use_cls=True, use_rec=True) # type: ignore
        
        # 兜底：如果 RapidOCR 完全没有识别到任何东西
        if not result or not hasattr(result, 'txts') or not result.txts: # type: ignore
            self.log("找字：未识别到任何文本！", 'debug')
            return None

        # 如果是全屏模式（1.0），偏移量就是 0；如果是裁剪区域，偏移量就是左上角起点
        offset_x = int(self.width * x1)
        offset_y = int(self.height * y1)

        result_dict = {}
        try:
            for i in range(len(result.txts)): # type: ignore
                word = result.txts[i] # type: ignore
                box = result.boxes[i] # type: ignore
                
                # box[0] 为左上角 [x, y]，box[2] 为右下角 [x, y]
                abs_x1 = int(box[0][0]) + offset_x
                abs_y1 = int(box[0][1]) + offset_y
                abs_x2 = int(box[2][0]) + offset_x
                abs_y2 = int(box[2][1]) + offset_y
                
                # 计算你原本逻辑中的宽高半径（沿用你原本的逻辑输出）
                w, h = (abs_x2 - abs_x1) // 2, (abs_y2 - abs_y1) // 2
                r = w if w <= h else h
                
                # 写入返回字典
                result_dict[word] = (abs_x1, abs_y1, r)

            if target_txt == '':
                if result_dict:
                    self.log('找字：'+str(result_dict), 'debug')
                return result_dict
            else:
                # 启用正则匹配
                if use_regex:
                    matched_results = {}
                    pattern = re.compile(target_txt)
                    for word in result_dict.keys():
                        if pattern.search(word):
                            matched_results[word] = result_dict[word]
                    if matched_results:
                        self.log('找字：'+str(matched_results), 'debug')
                    return matched_results
                else:
                    if result_dict:
                        self.log('找字：'+str(result_dict.get(target_txt, None)), 'debug')
                    return result_dict.get(target_txt, None)
                
        except (ConnectionError, TimeoutError, OSError) as e:
            self.log(f"设备连接异常: {e}", 'error')
            raise
        except Exception as e:
            self.log(f"文本识别逻辑处理出错: {e}", 'error')
            return None