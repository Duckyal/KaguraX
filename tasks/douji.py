import re
from module.adb import ADB


class Task_douji:
    def __init__(self, device:ADB, config:dict):
        self.op = device
        self.config = config

    def run(self):
        state = 0
        end = (int(self.op.width*3/5), int(self.op.height*9/10), int(self.op.height*1/10))
        self.op.图片预加载(
            'tasks/斗技图片/斗技页.png',
            'tasks/斗技图片/胜利.png',
            'tasks/斗技图片/失败.png',
            'tasks/斗技图片/胜场奖励.png',
            'tasks/斗技图片/头筹.png',
            'tasks/斗技图片/段位晋升.png',
            base_resolution=(self.op.width, self.op.height),
        )
        while True:
            self.op.sleep(0.5)
            result = self.op.找图()
            end_keys = [
                k for k in result
                if k.startswith('头筹_') or k.startswith('头筹.') or
                   k.startswith('失败_') or k.startswith('失败.') or
                   k.startswith('胜利_') or k.startswith('胜利.') or
                   k.startswith('胜场奖励_') or k.startswith('胜场奖励.')
            ]
            if end_keys:
                self.op.点击(*end)
            else:
                promo_key = next((k for k in result if k.startswith('段位晋升_') or k.startswith('段位晋升.')), None)
                if promo_key:
                    self.op.点击(*result[promo_key])
                elif next((k for k in result if k.startswith('斗技页_') or k.startswith('斗技页.')), None):    # 战前准备阶段
                    if self.config["count"] == "point":
                        result = self.op.找字(y1=0.6, x2=0.3, target_txt=r"[0-9]{2,}/[0-9]{2,}", use_regex=True)
                        if result is None:
                            continue
                        for key in result:
                            # 用正则精确提取 "数字/数字"，避免文本中含多个 '/' 导致 split 解包失败
                            match = re.search(r'(\d{2,})/(\d{2,})', key)
                            if not match:
                                continue
                            a, b = match.group(1), match.group(2)
                            if a == b:  # 脚本结束
                                state = 1
                            break
                        if state == 1:
                            self.op.log('斗技脚本结束')
                            break

                    while not state:
                        self.op.sleep(3)
                        result = self.op.找字()
                        if result is None:
                            continue
                        if '点击屏幕继续' in result:
                            break
                        if '自动' in result:
                            if '全部' not in result:
                                break
                            elif '取消' in result:
                                continue
                            else:
                                self.op.点击(*result['自动']) # 点击“自动上阵”
                        elif '手动' in result:
                            self.op.点击(*result['手动'])
                        elif '自动上阵' in result:
                            self.op.点击(*result['自动上阵'])
                        elif '准备' in result:
                            self.op.点击(*result['准备'])
                        elif '战' in result:
                            self.op.点击(*result['战'])
                else:        # 战斗中
                    texts = self.op.找字(y1=0.5)
                    if not texts:
                        continue
                    if "自动" in texts:
                        self.op.重置定时器()
                        self.op.sleep(10)
        