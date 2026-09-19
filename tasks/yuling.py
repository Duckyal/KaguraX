from module.adb import ADB


class Task_yuling:
    def __init__(self, device:ADB, config:dict):
        self.op = device
        self.config = config
        self.state = "N"
    
    def run(self):
        boss = self.config["boss"]
        layer = self.config["layer"]
        self.op.图片预加载(f"tasks/御灵图片/{boss}.png", f"tasks/御灵图片/{layer}.png", "tasks/御灵图片/未锁定.png", "tasks/御灵图片/御灵.png", base_resolution=(self.op.width, self.op.height))
        while True:
            self.op.sleep(1)
            result = self.op.找图()
            if result is None:
                break
            spirit_key = next((k for k in result if k.startswith('御灵_') or k.startswith('御灵.')), None)
            if spirit_key:
                self.op.点击(*result[spirit_key])
                continue
            boss_key = next((k for k in result if k.startswith(f'{boss}_') or k.startswith(f'{boss}.')), None)
            if boss_key:
                self.op.点击(*result[boss_key])
                self.op.sleep(1)
            layer_key = next((k for k in result if k.startswith(f'{layer}_') or k.startswith(f'{layer}.')), None)
            if layer_key:
                self.op.点击(*result[layer_key])
                self.op.sleep(1)
            unlock_key = next((k for k in result if k.startswith('未锁定_') or k.startswith('未锁定.')), None)
            if unlock_key:
                self.op.点击(*result[unlock_key])
                self.op.sleep(1)

        count = 0
        end = (int(self.op.width*3/5), int(self.op.height*9/10), int(self.op.height*1/10))
        self.op.图片预加载("tasks/御灵图片/奖励.png", "tasks/御灵图片/挑战.png", base_resolution=(self.op.width, self.op.height))
        while True:
            self.op.sleep(0.5)
            result = self.op.找图()
            if result is None:
                self.op.sleep(5)
                continue
            reward_key = next((k for k in result if k.startswith('奖励_') or k.startswith('奖励.')), None)
            if reward_key:
                self.op.点击(*end)
                self.op.sleep(1)
                if self.state == "N":
                    count += 1
                    self.state = "Y"
            else:
                challenge_key = next((k for k in result if k.startswith('挑战_') or k.startswith('挑战.')), None)
                if challenge_key:
                    self.op.点击(*result[challenge_key])
                    self.op.sleep(1)
                    self.state = "N"
            if count >= self.config["count"] and self.config["count"] != 0:
                self.op.log("已完成{count}次{boss}{layer}的挑战".format(count=count, boss=self.config["boss"], layer=self.config["layer"]))
                break
