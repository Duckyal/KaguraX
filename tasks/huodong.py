from module.adb import ADB

class Task_huodong:
    def __init__(self, device:ADB, config:dict):
        self.op = device
        self.config = config

    def run(self):
        if self.config.get("type") == "normal":
            self.sp()
        elif self.config.get("type") == "type1":
            self.type1()
        elif self.config.get("type") == "type2":
            self.type2()
        else:
            self.op.log(f"暂未支持该选项", level="error")

    def sp(self):
        n = int(self.config.get("count", 0))
        num = 0
        step = 'start'
        point = None
        self.op.图片预加载('tasks/活动图片/爬塔/start.png', 'tasks/活动图片/爬塔/over.png', base_resolution=(self.op.width, self.op.height))
        while True:
            self.op.sleep(0.5)
            result = self.op.找图(0.9)
            start_key = next((k for k in result if k.startswith('start_') or k.startswith('start.')), None)
            over_key = next((k for k in result if k.startswith('over_') or k.startswith('over.')), None)
            if start_key:
                if point is None:
                    point = result[start_key]
                self.op.点击(*point)
                step = 'start'
            elif over_key:
                if point is None:
                    self.op.点击(*result[over_key])
                else:
                    self.op.点击(point[0], point[1]-100, point[2])
                if step != 'over':
                    num += 1
                    self.op.log(f'已完成{num}次')
                    step = 'over'
                self.op.sleep(1)
            else:
                if step == 'start':
                    self.op.sleep(8)
                    step = 'wait'
            self.op.sleep(1)
            if num == n and n != 0:
                self.op.log(f'任务结束')
                break

    def type1(self):
        n = int(self.config.get("count", 0))
        num = 0
        step = 'start'
        self.op.图片预加载(
            "tasks/活动图片/爬塔/继续挑战.png",
            "tasks/活动图片/爬塔/开始挑战.png",
            "tasks/活动图片/爬塔/胜利.png",
            "tasks/活动图片/爬塔/失败.png",
            "tasks/活动图片/爬塔/搜寻.png",
            base_resolution=(self.op.width, self.op.height),
        )
        while True:
            self.op.sleep(0.5)
            result = self.op.找图()
            if not result:
                texts = self.op.找字(y1=0.5)
                if not texts:
                    continue
                elif "准备" in texts:
                    self.op.点击(*texts["准备"])  # type: ignore
                elif "手动" in texts:
                    self.op.点击(*texts["手动"])  # type: ignore
                elif "自动" in texts:
                    self.op.重置定时器()
                    self.op.sleep(10)
            else:
                search_key = next((k for k in result if k.startswith('搜寻_') or k.startswith('搜寻.')), None)
                if search_key:
                    if step == 'stop':
                        if num < n and n != 0:
                            num += 1
                            step = 'start'
                        elif num >= n:
                            self.op.log(f'任务结束')
                            break
                    self.op.点击(*result[search_key])
                    self.op.sleep(2)
                else:
                    continue_challenge_key = next((k for k in result if k.startswith('继续挑战_') or k.startswith('继续挑战.')), None)
                    if continue_challenge_key:
                        self.op.点击(*result[continue_challenge_key])
                        self.op.sleep(2)
                    else:
                        start_key = next((k for k in result if k.startswith('开始挑战_') or k.startswith('开始挑战.')), None)
                        if start_key:
                            self.op.点击(*result[start_key])
                            if step != "start":
                                step = 'start'
                            self.op.sleep(2)
                        else:
                            win_key = next((k for k in result if k.startswith('胜利_') or k.startswith('胜利.')), None)
                            if win_key:
                                self.op.点击(*result[win_key])
                                if step == 'start':
                                    num += 1
                                    self.op.log(f'已完成{num}次')
                                    step = 'stop'
                                self.op.sleep(1)
                            else:
                                fail_key = next((k for k in result if k.startswith('失败_') or k.startswith('失败.')), None)
                                if fail_key:
                                    self.op.点击(*result[fail_key])
                                    self.op.sleep(1)

    def type2(self):
        self.op.图片预加载(
            "tasks/活动图片/大富翁/一锤定音.png",
            "tasks/活动图片/大富翁/借运化吉.png",
            "tasks/活动图片/大富翁/出发.png",
            "tasks/活动图片/大富翁/出巡仪式.png",
            "tasks/活动图片/大富翁/胜利.png",
            "tasks/活动图片/大富翁/结束.png",
            base_resolution=(self.op.width, self.op.height),
        )
        while True:
            self.op.check_stop()
            _last = self.op.找图(sim=0.9, priority_corner="br")
            _last = locals().get('_last', {})
            if any(str(k).startswith('结束_') or str(k).startswith('结束.') for k in _last):
                return
            elif any(str(k).startswith('一锤定音_') or str(k).startswith('一锤定音.') for k in _last):
                txts = self.op.找字(y1=0.5, target_txt=r"\d", use_regex=True)
                if txts:
                    max_num = str(max((k for k in txts.keys() if k.replace('.', '', 1).isdigit()), key=lambda k: float(k)))
                    self.op.点击(txts[max_num][0]-txts[max_num][2], txts[max_num][1]+2*txts[max_num][2], txts[max_num][2]//2)
            elif any(str(k).startswith('借运化吉_') or str(k).startswith('借运化吉.') for k in _last):
                if _last:
                    self.op.点击(*list(_last.values())[0])
            elif any(str(k).startswith('胜利_') or str(k).startswith('胜利.') for k in _last):
                if _last:
                    self.op.点击(*list(_last.values())[0])
            elif any(str(k).startswith('出巡仪式_') or str(k).startswith('出巡仪式.') for k in _last):
                if _last:
                    self.op.点击(*list(_last.values())[0])
            elif any(str(k).startswith('出发_') or str(k).startswith('出发.') for k in _last):
                self.op.点击(*list(_last.values())[0])
            self.op.sleep(3.0)