from module.adb import ADB


class Task_k28:
    def __init__(self, device:ADB, config:dict):
        self.op = device
        self.config = config

            
    def run(self):
        end = (int(self.op.width*3/5), int(self.op.height*9/10), int(self.op.height*1/10))
        self.op.图片预加载("tasks/k28图片/k28.png", "tasks/k28图片/退出.png", "tasks/k28图片/确认退出.png", base_resolution=(self.op.width, self.op.height))
        while True:
            self.op.sleep(1)
            result = self.op.找图()
            k28_key = next((k for k in result if k.startswith('k28_') or k.startswith('k28.')), None)
            if k28_key:
                break
            confirm_exit_key = next((k for k in result if k.startswith('确认退出_') or k.startswith('确认退出.')), None)
            if confirm_exit_key:
                self.op.点击(*result[confirm_exit_key])
            else:
                exit_key = next((k for k in result if k.startswith('退出_') or k.startswith('退出.')), None)
                if exit_key:
                    self.op.点击(*result[exit_key])

        imgs = ('tasks/k28图片/k28.png', "tasks/k28图片/探索.png", "tasks/k28图片/自动轮换.png",
                "tasks/k28图片/轮换设置.png", "tasks/k28图片/小怪.png", "tasks/k28图片/大怪.png",
                "tasks/k28图片/奖励.png", "tasks/k28图片/失败.png", "tasks/k28图片/通关奖励.png",
                "tasks/k28图片/结界突破.png", "tasks/k28图片/掉落宝箱.png", "tasks/k28图片/退出.png")
        self.op.图片预加载(*imgs, base_resolution=(self.op.width, self.op.height))
        state = 0
        while True:
            self.op.sleep(0.5)
            result = self.op.找图(priority_corner="br")
            if result is None:
                self.op.sleep(2)
                continue
            k28_key = next((k for k in result if k.startswith('k28_') or k.startswith('k28.')), None)
            break_key = next((k for k in result if k.startswith('结界突破_') or k.startswith('结界突破.')), None)
            if k28_key and break_key:
                if self.config["count"] != 0:   # 有突破券上限
                    result_txt = self.op.找字(x1=0.5, x2=0.7, y2=0.2, target_txt=r".*\d+/30.*", use_regex=True)
                    if result_txt:
                        import re
                        match = re.search(r"(\d+)/30", list(result_txt.keys())[0])
                        if match:
                            a = match.group(1)
                            print(a, result_txt)
                            if int(a) >= int(self.config["count"]):
                                self.op.log("突破券已达{count}上限，运行突破任务".format(count=a))
                                self.op.点击(*result[break_key])
                                from tasks.tupo import Task_tupo
                                Task_tupo(self.op, self.config).refresh_kekkai()
                                self.op.图片预加载(*imgs, base_resolution=(self.op.width, self.op.height))
                                state = 0
                            else:
                                self.op.点击(*result[k28_key])
                        else:
                            self.op.点击(*result[k28_key])
            else:
                explore_key = next((k for k in result if k.startswith('探索_') or k.startswith('探索.')), None)
                if explore_key:
                    self.op.点击(*result[explore_key])
                else:
                    switch_key = next((k for k in result if k.startswith('轮换设置_') or k.startswith('轮换设置.')), None)
                    auto_key = next((k for k in result if k.startswith('自动轮换_') or k.startswith('自动轮换.')), None)
                    if (switch_key and state == 0) or auto_key:
                        if auto_key:
                            self.op.点击(*result[auto_key])
                            self.op.sleep(1)
                        if self.config["lunhuan"] != "None":
                            self.op.点击(*result[switch_key])
                            self.lunhuan()
                            self.op.图片预加载(*imgs, base_resolution=(self.op.width, self.op.height))
                        state = 1
                    else:
                        box_key = next((k for k in result if k.startswith('掉落宝箱_') or k.startswith('掉落宝箱.')), None)
                        if box_key:
                            self.op.点击(*result[box_key])
                        else:
                            reward_key = next((k for k in result if k.startswith('奖励_') or k.startswith('奖励.')), None)
                            if reward_key:
                                self.op.点击(*end)
                            else:
                                fail_key = next((k for k in result if k.startswith('失败_') or k.startswith('失败.')), None)
                                if fail_key:
                                    from module.decorators import TaskStoppedException
                                    raise TaskStoppedException("k28任务异常退出：请检查式神御魂", "error")
                                else:
                                    reward2_key = next((k for k in result if k.startswith('通关奖励_') or k.startswith('通关奖励.')), None)
                                    if reward2_key:
                                        self.op.点击(*result[reward2_key])
                                    else:
                                        big_key = next((k for k in result if k.startswith('大怪_') or k.startswith('大怪.')), None)
                                        if big_key:
                                            self.op.点击(*result[big_key])
                                            self.op.sleep(0.5)
                                        else:
                                            small_key = next((k for k in result if k.startswith('小怪_') or k.startswith('小怪.')), None)
                                            if small_key:
                                                self.op.点击(*result[small_key])
                                                self.op.sleep(0.5)
                                            else:
                                                exit_key = next((k for k in result if k.startswith('退出_') or k.startswith('退出.')), None)
                                                if exit_key and state == 1:
                                                    self.op.点击(int(self.op.width*0.75), int(self.op.height*0.8), int(self.op.height*0.01))
                                                    self.op.sleep(2)
            
    def lunhuan(self):
        self.op.图片预加载("tasks/k28图片/全部.png", f"tasks/k28图片/{self.config['lunhuan']}.png", "tasks/k28图片/二星.png", "tasks/k28图片/候补式神.png", base_resolution=(self.op.width, self.op.height))
        state = 0
        while True:
            self.op.check_stop()
            result = self.op.找图(priority_corner="tr")
            star_key = next((k for k in result if k.startswith('二星_') or k.startswith('二星.')), None)
            if star_key and state == 0:
                self.op.点击(*result[star_key])
                state += 1
            lunhuan_key = next((k for k in result if k.startswith(f"{self.config['lunhuan']}_") or k.startswith(f"{self.config['lunhuan']}.")), None)
            if lunhuan_key and state == 2:
                self.op.点击(*result[lunhuan_key])
                state += 1
                self.op.sleep(1)
            else:
                backup_key = next((k for k in result if k.startswith('候补式神_') or k.startswith('候补式神.')), None)
                if backup_key and state == 3:
                    self.op.点击(*result[backup_key])
                    state += 1
                elif state == 1:
                    all_key = next((k for k in result if k.startswith('全部_') or k.startswith('全部.')), None)
                    if all_key:
                        self.op.点击(*result[all_key])
                        state += 1
                        self.op.sleep(1)
            if state == 4:
                break

        self.op.图片预加载("tasks/k28图片/滑块.png", "tasks/k28图片/二星.png", "tasks/k28图片/确定设置.png", base_resolution=(self.op.width, self.op.height))
        while True:
            self.op.check_stop()
            result = self.op.找图(priority_corner="br")
            star_key = next((k for k in result if k.startswith('二星_') or k.startswith('二星.')), None)
            confirm_key = next((k for k in result if k.startswith('确定设置_') or k.startswith('确定设置.')), None)
            slider_key = next((k for k in result if k.startswith('滑块_') or k.startswith('滑块.')), None)
            if star_key and confirm_key and slider_key:
                if result[star_key][1] <= self.op.height*0.6:
                    x , y, r, *els = result[slider_key]
                    self.op.滑动(x, y, x+r, y, 5)
                else:
                    self.op.长按(result[star_key][0], result[star_key][1], 3)
                    self.op.sleep(1)
                    self.op.点击(*result[confirm_key])
                    break
                   
        self.op.sleep(1)
