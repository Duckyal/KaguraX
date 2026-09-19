/**
 * 应用主入口
 */
const App = {
  deviceName: '',
  startBtn: null, // 保存按钮元素的引用

  init() {
    // 生成任务列表 (必须在其他模块初始化前执行)
    this.generateTaskList();

    // 从 URL 获取设备名
    const params = new URLSearchParams(window.location.search);
    this.deviceName = params.get('device') || '';

    // 初始化模块
    if (typeof TerminalManager !== 'undefined') TerminalManager.init();
    if (typeof TaskManager !== 'undefined') TaskManager.init();

    // 绑定事件
    this.bindEvents();
    
    // 启动状态轮询
    this.startPolling();
  },

  generateTaskList() {
    // 自动扫描页面中的任务面板，生成侧边栏列表
    const taskList = document.getElementById('taskList');
    const panels = document.querySelectorAll('.task-panel');
    
    if (!taskList || panels.length === 0) return;

    taskList.innerHTML = ''; // 清空现有静态内容

    // 恢复上次浏览的任务面板：dev/home 页切换回来时保持所在任务页
    // （同自定义任务页，用 localStorage 记忆）
    let defaultIndex = 0;
    if (typeof TaskManager !== 'undefined' && TaskManager.getLastTask) {
      const lastTask = TaskManager.getLastTask();
      if (lastTask) {
        const idx = Array.prototype.findIndex.call(panels, p => p.id === `panel-${lastTask}`);
        if (idx >= 0) defaultIndex = idx;
      }
    }

    panels.forEach((panel, index) => {
      // 解析 ID: panel-yuhun -> yuhun
      const taskId = panel.id.replace('panel-', '');
      // 读取 data-name，如果没有则用 ID 代替
      const taskName = panel.dataset.name || taskId;

      // 创建 li 元素
      const li = document.createElement('li');
      li.className = 'sidebar-task__item';
      li.dataset.task = taskId;
      li.dataset.name = taskName;
      li.textContent = taskName;

      // 默认激活上次浏览的任务面板；无记录时激活第一个
      if (index === defaultIndex) {
        li.classList.add('active');
        panel.style.display = 'block';
        // 把任务名初始化到标题栏
        document.getElementById('taskName').textContent = taskName;
      } else {
        panel.style.display = 'none';
      }

      taskList.appendChild(li);
    });
  },

  bindEvents() {
    // 获取按钮并保存引用
    this.startBtn = document.getElementById('startBtn');
    if (this.startBtn) {
      this.startBtn.addEventListener('click', () => {
        // 智能判断：看按钮文字是“执行”还是“终止”
        if (this.startBtn.textContent.includes('终止')) {
          this.stopTask();
        } else {
          this.startTask();
        }
      });
    }
  },

  // ================= 业务逻辑区 =================
  // 启动任务 
  async startTask() {
    const { task, config } = TaskManager.getCurrentConfig();

    const activeItem = document.querySelector('.sidebar-task__item.active');
    const displayName = activeItem ? activeItem.dataset.name : task;
    
    if (typeof TerminalManager !== 'undefined') {
        TerminalManager.addLog(`准备启动任务: ${displayName}`, 'info');
    }

    const payload = {
      task: task,
      device: this.deviceName,
      config: {
        ...config,
        enable_match_debug: true
      }
    };

    try {
      const response = await fetch('/api/start_task', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload)
      });

      if (response.ok) {
        const result = await response.json();
        if (typeof TerminalManager !== 'undefined') {
            TerminalManager.addLog('任务已成功提交至后端执行', 'success');
        }
      } else {
        const errText = await response.text();
        if (typeof TerminalManager !== 'undefined') {
            TerminalManager.addLog(`后端返回错误: ${errText}`, 'error');
        }
      }
    } catch (error) {
        if (typeof TerminalManager !== 'undefined') {
            TerminalManager.addLog(`任务发送失败: ${error.message}`, 'error');
        }
    }
  },

  // 停止任务逻辑
  async stopTask() {
    if(!confirm("确定要停止当前任务吗？")) return;

    try {
      const response = await fetch('/api/stop_task', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ device: this.deviceName })
      });

      const data = await response.json();
      if(data.success) {
        this.checkTaskStatus(); // 立即刷新状态
      }
    } catch (error) {
      alert('停止请求失败');
    }
  },

  // ================= 状态轮询区 =================

  // 启动轮询
  startPolling() {
    this.checkTaskStatus(); // 立即执行一次
    setInterval(() => {
      this.checkTaskStatus();
    }, 2000); // 每 2 秒轮询
  },

  // 查询状态
  async checkTaskStatus() {
    if (!this.deviceName) return;

    try {
      const res = await fetch(`/api/task_status?device=${encodeURIComponent(this.deviceName)}`);
      const data = await res.json();
      this.updateActionButton(data.running, data.task_name);
    } catch (err) {
      console.error("状态查询失败", err);
    }
  },

  // 更新按钮 UI
  updateActionButton(isRunning, taskName) {
    if (!this.startBtn) return;

    const mainContainer = document.querySelector('.main-container');

    if (isRunning) {
      // --- 状态：正在运行 ---
      this.startBtn.textContent = "终止任务";
      this.startBtn.style.backgroundColor = "#dc3545"; // 红色
      this.startBtn.style.color = "#fff";
      this.startBtn.style.borderColor = "#dc3545";
      
      // 添加运行状态类，让终端占 2/3
      if (mainContainer) {
        mainContainer.classList.add('task-running');
      }

    } else {
      // --- 状态：空闲 ---
      this.startBtn.textContent = "执行脚本";
      this.startBtn.style.backgroundColor = ""; // 恢复 CSS 默认
      this.startBtn.style.color = "";
      this.startBtn.style.borderColor = "";
      
      // 移除运行状态类，恢复 1/2 布局
      if (mainContainer) {
        mainContainer.classList.remove('task-running');
      }
    }
  }
};

// DOM 加载完成后初始化应用
document.addEventListener('DOMContentLoaded', () => {
  App.init();
});