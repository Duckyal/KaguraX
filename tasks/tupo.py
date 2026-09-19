from module.adb import ADB
import re

class Task_tupo:
    def __init__(self, device:ADB, config:dict):
        self.op = device
        self.config = config
        self.end = (int(self.op.width*3/5), int(self.op.height*9/10), int(self.op.height*1/10))

    def run(self):
        type = self.config.get("type")
        # 找模式：只传模板名，不写死分辨率；内部会按当前设备自动选最合适的模板版本
        self.op.图片预加载(
            f"tasks/突破图片/{type}.png",
            "tasks/突破图片/退出.png",
            base_resolution=(self.op.width, self.op.height),
        )
        while True:
            self.op.sleep(1)
            result = self.op.找图()
            if not result:
                continue
            match_key = next((k for k in result if k.startswith(f"{type}_") or k.startswith(f"{type}.")), None)
            if match_key:
                self.op.点击(*result[match_key])
            else:
                exit_key = next((k for k in result if k.startswith("退出_") or k.startswith("退出.")), None)
                if exit_key:
                    break
        if type == "个人突破":
            self.refresh_kekkai()
        else:
            self.refresh_ryou()
            
    def refresh_kekkai(self):
        self.op.log(f"准备运行个人突破")
        refresh = self.config.get("refresh", "yes")
        self.op.图片预加载(
            "tasks/突破图片/入口.png",
            "tasks/突破图片/进攻.png",
            "tasks/突破图片/刷新.png",
            "tasks/突破图片/确定刷新.png",
            "tasks/突破图片/奖励.png",
            "tasks/突破图片/失败.png",
            "tasks/突破图片/未锁定.png",
            "tasks/突破图片/退出.png",
            base_resolution=(self.op.width, self.op.height),
        )
        while True:
            self.op.sleep(1)
            result = self.op.找图()
            if not result:
                self.op.sleep(3)
                continue
            key_unlocked = next((k for k in result if k.startswith("未锁定_") or k.startswith("未锁定.")), None)
            if key_unlocked:
                self.op.点击(*result[key_unlocked])
            else:
                key_refresh_confirm = next((k for k in result if k.startswith("确定刷新_") or k.startswith("确定刷新.")), None)
                if key_refresh_confirm:
                    self.op.点击(*result[key_refresh_confirm])
                else:
                    key_attack = next((k for k in result if k.startswith("进攻_") or k.startswith("进攻.")), None)
                    if key_attack:
                        self.op.点击(*result[key_attack])
                        self.op.sleep(int(self.config.get("sleep", 5)))
                    else:
                        reward_key = next((k for k in result if k.startswith("奖励_") or k.startswith("奖励.")), None)
                        fail_key = next((k for k in result if k.startswith("失败_") or k.startswith("失败.")), None)
                        if reward_key or fail_key:
                            self.op.点击(*self.end)
                        else:
                            entry_key = next((k for k in result if k.startswith("入口_") or k.startswith("入口.")), None)
                            exit_key = next((k for k in result if k.startswith("退出_") or k.startswith("退出.")), None)
                            if entry_key and exit_key:
                                x, y, r = result[entry_key][0], result[entry_key][1], result[entry_key][2]
                                result_txt = self.op.找字(x1=0.7, y2=0.1, target_txt=r"\d+/30", use_regex=True)
                                if result_txt:
                                    a = list(result_txt.keys())[0].split("/")[0]
                                    if a == "0":
                                        self.op.点击(*result[exit_key])
                                        self.op.sleep(3)
                                        if not any(k.startswith("退出_") or k.startswith("退出.") for k in self.op.找图() or {}):
                                            break
                                    else:
                                        self.op.点击(x-r, y+r, r)
                                        self.op.sleep(1)
                            else:
                                refresh_key = next((k for k in result if k.startswith("刷新_") or k.startswith("刷新.")), None)
                                if refresh_key:
                                    if refresh == "yes":
                                        self.op.点击(*result[refresh_key])
                                    else:
                                        break
        self.op.log(f"个人突破完成")
                
    def refresh_ryou(self):
        self.op.log(f"开始运行寮突破")
        count = 0
        self.op.图片预加载(
            "tasks/突破图片/入口.png",
            "tasks/突破图片/奖励.png",
            "tasks/突破图片/失败.png",
            "tasks/突破图片/进攻.png",
            "tasks/突破图片/未锁定.png",
            "tasks/突破图片/退出.png",
            "tasks/突破图片/滑块.png",
            base_resolution=(self.op.width, self.op.height),
        )
        while True:
            self.op.sleep(0.5)
            state = 0
            result = self.op.找图()
            if not result:
                self.op.sleep(3)
                continue
            unlocked_key = next((k for k in result if k.startswith("未锁定_") or k.startswith("未锁定.")), None)
            if unlocked_key:
                self.op.点击(*result[unlocked_key])
            else:
                attack_key = next((k for k in result if k.startswith("进攻_") or k.startswith("进攻.")), None)
                if attack_key:
                    self.op.点击(*result[attack_key])
                    self.op.sleep(int(self.config.get("sleep", 5)))
                else:
                    reward_key = next((k for k in result if k.startswith("奖励_") or k.startswith("奖励.")), None)
                    fail_key = next((k for k in result if k.startswith("失败_") or k.startswith("失败.")), None)
                    if reward_key or fail_key:
                        self.op.点击(*self.end)
                    else:
                        entry_key = next((k for k in result if k.startswith("入口_") or k.startswith("入口.")), None)
                        exit_key = next((k for k in result if k.startswith("退出_") or k.startswith("退出.")), None)
                        if entry_key and exit_key:
                            x, y, r = result[entry_key][0], result[entry_key][1], result[entry_key][2]
                            result_txt = self.op.找字(x1=0.1, y1=0.5, x2=0.3, y2=0.9)
                            if result_txt:
                                for item in result_txt:
                                    match1 = re.search(r"(\d+(\.\d+)?)%", item)
                                    if match1:
                                        percent = float(match1.group(1))
                                        if percent >= 90:
                                            state += 1
                                            break
                                    match2 = re.search(r"击败次数[：:]?\s*(\d+)/6", item)
                                    if match2:
                                        a = match2.group(1)
                                        if a == "0":
                                            if self.config.get("refresh") == "no":
                                                self.op.点击(*result[exit_key])
                                                state += 1
                                            else:
                                                self.op.log("等待寮突破次数增加")
                                                self.op.sleep(300)
                                            break
                                if state == 0:
                                    self.op.点击(x-2*r, y+r, r)
                                else:
                                    self.op.log("寮突破任务结束")
                                    break
                        else:
                            slider_key = next((k for k in result if k.startswith("滑块_") or k.startswith("滑块.")), None)
                            if slider_key and not entry_key:
                                count += 1
                                x, y, r = result[slider_key][0], result[slider_key][1], result[slider_key][2]
                                self.op.滑动(x, y, int(x-r/10), y+r, 5)
                            if count >= 3:
                                self.op.log("寮突破异常结束：无可突破对象")
                                break