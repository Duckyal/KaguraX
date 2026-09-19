from module.adb import ADB

class Task_yuhun:
    def __init__(self, device:ADB, config:dict):
        self.op = device
        self.config = config
        self.结束点击 = (int(self.op.width*4/5), int(self.op.height*4/5), int(self.op.height*1/10))
        self.state = "N"
    
    def run(self):
        type = self.config.get("type")
    
        if type == "八岐大蛇":
            self.八岐大蛇()
        elif type == "业原火":
            self.业原火()

    def 八岐大蛇(self):
        team = self.config.get("team")
        count = int(self.config.get("count"))
        num = 0
        if team == "leader":
            self.op.图片预加载("tasks/御魂图片/八岐大蛇/失败.png", "tasks/御魂图片/八岐大蛇/结束.png", "tasks/御魂图片/八岐大蛇/挑战.png",
                          "tasks/御魂图片/八岐大蛇/拒绝悬赏.png", base_resolution=(self.op.width, self.op.height))
            while True:
                self.op.sleep(1)
                result = self.op.找图()
                if not result:
                    self.op.sleep(2)
                else:
                    refuse_key = next((k for k in result if k.startswith('拒绝悬赏_') or k.startswith('拒绝悬赏.')), None)
                    if refuse_key:
                        self.op.点击(*result[refuse_key])
                    else:
                        challenge_key = next((k for k in result if k.startswith('挑战_') or k.startswith('挑战.')), None)
                        if challenge_key:
                            self.op.点击(*result[challenge_key])
                            if self.state == "Y":
                                self.state = "N"
                        else:
                            fail_key = next((k for k in result if k.startswith('失败_') or k.startswith('失败.')), None)
                            if fail_key:
                                self.op.点击(*self.结束点击)
                                from module.decorators import TaskStoppedException
                                raise TaskStoppedException("八岐大蛇任务失败：请检查阵容和御魂是否正确")
                            else:
                                end_key = next((k for k in result if k.startswith('结束_') or k.startswith('结束.')), None)
                                if end_key:
                                    self.op.点击(*self.结束点击)
                                    if self.state == "N":
                                        self.state = "Y"
                                        num += 1
                                        self.op.log(f"已挑战{num}次")
                                    if num >= count and count != 0:
                                        self.op.log("完成御魂任务")
                                        break
        else:
            self.op.图片预加载("tasks/御魂图片/八岐大蛇/失败.png", "tasks/御魂图片/八岐大蛇/结束.png", "tasks/御魂图片/八岐大蛇/拒绝悬赏.png",
                          "tasks/御魂图片/八岐大蛇/组队页.png", base_resolution=(self.op.width, self.op.height))
            while True:
                self.op.sleep(1)
                result = self.op.找图()
                if not result:
                    self.op.sleep(2)
                else:
                    refuse_key = next((k for k in result if k.startswith('拒绝悬赏_') or k.startswith('拒绝悬赏.')), None)
                    if refuse_key:
                        self.op.点击(*result[refuse_key])
                    else:
                        team_key = next((k for k in result if k.startswith('组队页_') or k.startswith('组队页.')), None)
                        if team_key:
                            if self.state == "Y":
                                self.state = "N"
                        else:
                            fail_key = next((k for k in result if k.startswith('失败_') or k.startswith('失败.')), None)
                            if fail_key:
                                self.op.点击(*self.结束点击)
                                from module.decorators import TaskStoppedException
                                raise TaskStoppedException("八岐大蛇任务失败：请检查阵容和御魂是否正确")
                            else:
                                end_key = next((k for k in result if k.startswith('结束_') or k.startswith('结束.')), None)
                                if end_key:
                                    self.op.点击(*self.结束点击)
                                    if self.state == "N":
                                        self.state = "Y"
                                        num += 1
                                        self.op.log(f"已挑战{num}次")
                                    if num >= count and count != 0:
                                        self.op.log("完成御魂任务")
                                        break

    def 业原火(self):
        count = 0
        self.op.图片预加载("tasks/御魂图片/业原火/未锁定.png", "tasks/御魂图片/业原火/挑战.png", "tasks/御魂图片/业原火/胜利.png", "tasks/御魂图片/业原火/失败.png", base_resolution=(self.op.width, self.op.height))
        while True:
            self.op.sleep(0.5)
            result = self.op.找图()
            if not result:
                self.op.sleep(3)
                continue
            unlock_key = next((k for k in result if k.startswith('未锁定_') or k.startswith('未锁定.')), None)
            if unlock_key:
                self.op.点击(*result[unlock_key])
                self.op.sleep(0.5)
            else:
                challenge_key = next((k for k in result if k.startswith('挑战_') or k.startswith('挑战.')), None)
                if challenge_key:
                    self.op.点击(*result[challenge_key])
                    self.state = "N"
                    self.op.sleep(0.5)
                else:
                    win_key = next((k for k in result if k.startswith('胜利_') or k.startswith('胜利.')), None)
                    if win_key:
                        self.op.点击(*self.结束点击)
                        if self.state == "N":
                            count += 1
                            self.state = "Y"
                        self.op.log(f"已完成{count}次")
                        self.op.sleep(0.5)
                    else:
                        fail_key = next((k for k in result if k.startswith('失败_') or k.startswith('失败.')), None)
                        if fail_key:
                            from module.decorators import TaskStoppedException
                            raise TaskStoppedException("任务失败:可能阵容式神未搭配御魂")

            if self.config.get("count") != 0:
                if count >= int(self.config.get("count", 100)):
                    self.op.log("业原火任务完成")
                    break