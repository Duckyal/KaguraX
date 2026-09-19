/**
 * static/js/float/float.js —— 悬浮窗遥控页逻辑（/float）
 *
 * 职责：小窗里快速「选设备 → 选任务(可改常用参数) → 运行/终止」
 * - 设备：/api/get_devices 动态拉取，localStorage 记忆上次所选
 * - 任务：不内置任务表，直接抓取 /home 页面中由 home/app.js 生成的同一批
 *   任务面板（.task-panel，排除「自定义」）解析为字段表；以后加任务只需
 *   在 home.html 增加配置面板，本页自动同步。
 * - 参数默认值：优先回填该设备最近一次启动该任务时的配置(/api/task_last_configs)，
 *   没有则用面板默认；也可以直接手改
 * - 运行中：轮询 /api/task_status，大按钮切换为「终止」；WebSocket 订阅最近 1~2 行日志
 */

/* ==================== 悬浮窗自绘下拉（替代原生 <select>） ====================
 * 现象：悬浮窗 WebView 是系统悬浮窗（无 Activity 弹窗上下文），点原生 <select>
 *       时 Android 要弹出的系统选择列表无法显示 → 点不开（网页/全屏 WebView 正常）。
 * 方案：仅悬浮窗环境(UA 带 KaguraXFloat)启用。原生 <select> 视觉隐藏但保留在
 *       DOM，所有读写在它身上照常进行；外层换成按钮 + 页内 DOM 浮层列表。
 *       选中会回写 <select>.value 并派发 change，设备切换/依赖联动/参数收集等
 *       既有逻辑完全不用感知自绘的存在。
 */
let __fsel_openRoot = null;   // 同时只展开一个下拉

function __fsel_forceClose() {
  const r = __fsel_openRoot;
  if (r && r.dataset.open === '1') {
    const p = r.querySelector('.fsel-panel');
    if (p) p.hidden = true;
    delete r.dataset.open;
  }
  __fsel_openRoot = null;
}

/** 包装原生 select 为自绘下拉；非悬浮窗环境返回 null（保持原生）。返回 { sync, close } */
function buildFancySelect(select) {
  if (!select || select.tagName !== 'SELECT') return null;
  if (!/KaguraXFloat/i.test(navigator.userAgent)) return null;
  if (select.dataset.fsel) return null;
  const parent = select.parentNode;
  if (!parent) return null;

  select.dataset.fsel = '1';
  select.classList.add('fsel-native');   // 视觉隐藏，但 value/options 仍可正常读写

  const root = document.createElement('div');
  root.className = 'fsel';
  const btn = document.createElement('button');
  btn.type = 'button';
  btn.className = 'fsel-btn';
  const panel = document.createElement('div');
  panel.className = 'fsel-panel';
  panel.hidden = true;

  root.appendChild(btn);
  root.appendChild(panel);
  parent.insertBefore(root, select);
  root.appendChild(select);

  function curLabel() {
    const i = select.selectedIndex;
    const o = i >= 0 ? select.options[i] : null;
    return o ? (o.textContent || o.value || '') : '';
  }

  function buildPanel() {
    panel.innerHTML = '';
    Array.from(select.options).forEach(opt => {
      const item = document.createElement('button');
      item.type = 'button';
      item.className = 'fsel-item' + (opt.selected ? ' active' : '');
      item.hidden = opt.style.display === 'none';   // 依赖联动隐藏的选项同样不可见
      item.textContent = opt.textContent || opt.value || '';
      item.addEventListener('click', () => {
        select.value = opt.value;
        select.dispatchEvent(new Event('change', { bubbles: true }));
        sync();
        close();
      });
      panel.appendChild(item);
    });
  }

  function place() {
    const r = root.getBoundingClientRect();
    const vw = window.innerWidth, vh = window.innerHeight;
    const minW = Math.max(r.width, 170);
    const left = Math.max(8, Math.min(r.left, vw - minW - 8));
    panel.style.minWidth = Math.min(minW, vw - 16) + 'px';
    panel.style.left = left + 'px';
    const gap = 6;
    if (vh - r.bottom - gap >= 150) {         // 下方放得下 → 向下展开
      panel.style.top = (r.bottom + gap) + 'px';
      panel.style.bottom = 'auto';
      panel.style.maxHeight = Math.max(120, vh - r.bottom - gap - 4) + 'px';
    } else {                                  // 否则向上展开
      panel.style.top = 'auto';
      panel.style.bottom = (vh - r.top + gap) + 'px';
      panel.style.maxHeight = Math.max(120, r.top - gap - 4) + 'px';
    }
  }

  function open() {
    __fsel_forceClose();                       // 关掉其它展开的下拉
    sync();
    place();
    panel.hidden = false;
    root.dataset.open = '1';
    __fsel_openRoot = root;
  }
  function close() {
    panel.hidden = true;
    delete root.dataset.open;
    if (__fsel_openRoot === root) __fsel_openRoot = null;
  }
  function sync() {
    btn.textContent = curLabel();
    buildPanel();
  }

  btn.addEventListener('pointerdown', (e) => {
    e.preventDefault();      // 避免触发原生控件的默认聚焦/点开
    e.stopPropagation();
  });
  btn.addEventListener('click', (e) => {
    e.stopPropagation();
    if (root.dataset.open === '1') close(); else open();
  });
  select.addEventListener('change', sync);    // 依赖联动等外部改 select 时同步外观
  sync();

  return { sync, close };
}

/* 点击下拉以外区域关闭已展开的面板 */
function __fsel_docClose(e) {
  if (__fsel_openRoot && __fsel_openRoot.dataset.open === '1' &&
      !__fsel_openRoot.contains(e.target)) {
    __fsel_forceClose();
  }
}
document.addEventListener('pointerdown', __fsel_docClose);
document.addEventListener('click', __fsel_docClose);   // 兼容无 Pointer Events 的旧 WebView

const FloatApp = {
  PAGE_DEVICE: (window.PAGE_DEVICE || '').trim(),
  device: '',
  devices: [],
  lastConfigs: {},          // 当前设备: { task_name: config }
  tasks: [],                // 当前展示的任务 id 列表
  activeTask: '',

  running: false,
  runningTaskName: '',
  busy: false,              // 启动/停止请求中
  pollTimer: null,
  // 悬浮窗小窗(UA 带 KaguraXFloat)= env-float：日志只显示最新一行；
  // App「打开网页」全屏 / 浏览器 = env-full：日志多行铺满剩余高度
  isFloatEnv: /KaguraXFloat/i.test(navigator.userAgent),

  els: {},
  ws: null,

  /* 任务 schema：不再内置。运行时抓取 /home 页面里 home/app.js 生成（展示）的
   * 同一批 .task-panel（排除「自定义」），解析为以下结构的字段表：
   *   { task, label, fields: [{ name,label,kind,def,min,max,step,note,options,depends }] }
   * kind: select | number | text
   * option: [value, label]
   * depends: { on, when: { value: [允许的option value] } }  简单联动（同 /home）
   */
  TASK_META: [],
  CUSTOM_TASK: 'custom',

  init() {
    this.els = {
      deviceSel: document.getElementById('deviceSel'),
      deviceRefreshBtn: document.getElementById('deviceRefreshBtn'),
      devHint: document.getElementById('devHint'),
      chipRow: document.getElementById('chipRow'),
      cfgBody: document.getElementById('cfgBody'),
      customHint: document.getElementById('customHint'),
      actionBtn: document.getElementById('actionBtn'),
      actionLabel: document.getElementById('actionLabel'),
      actionIcon: document.getElementById('actionIcon'),
      floatCollapseBtn: document.getElementById('floatCollapseBtn'),
      stateDot: document.getElementById('stateDot'),
      stateText: document.getElementById('stateText'),
      runningTask: document.getElementById('runningTask'),
      lastLog: document.getElementById('lastLog'),
      toast: document.getElementById('toast')
    };

    // 悬浮窗(WebView 浮层)里原生 <select> 弹不出系统选择列表 → 自绘下拉
    if (this.isFloatEnv && this.els.deviceSel) {
      this.deviceFsel = buildFancySelect(this.els.deviceSel);
    }
    // 设备下拉变化统一走 onDeviceChange（loadDevices 载入后也会主动触发一次）
    this.els.deviceSel.addEventListener('change', () => this.onDeviceChange());

    this.els.deviceRefreshBtn.addEventListener('click', () => this.refreshDevices());
    this.els.actionBtn.addEventListener('click', () => this.onActionClick());

    // 收起：KaguraX 徽标即收起按钮（徽标 + 收起合一）。仅悬浮窗环境响应，
    // 完整页/浏览器没有悬浮球可收，徽标保持纯展示不绑定收起
    this.els.floatCollapseBtn.addEventListener('click', () => {
      if (this.isFloatEnv) this.collapseFloat();
    });

    this.connectWS();
    this.loadDevices();
    this.loadTaskSchemas();   // 异步抓取 /home 任务面板 → 填充 TASK_META
    this.startPolling();
  },

  /* ==================== 设备 ==================== */
  async loadDevices() {
    this.els.deviceRefreshBtn.classList.add('spinning');
    try {
      // 带上最近使用的设备地址作 hint：无线设备若掉线，后端会据此幂等 adb connect 拉回
      let hint = this.PAGE_DEVICE;
      if (!hint) {
        try { hint = localStorage.getItem('onmyoji:float:device') || ''; } catch (e) { hint = ''; }
      }
      const q = hint ? '?hint=' + encodeURIComponent(hint) : '';
      const resp = await fetch('/api/get_devices' + q, { cache: 'no-store' });
      const data = await resp.json();
      this.devices = (data && data.devices) || [];
    } catch (e) {
      this.devices = [];
    }
    this.els.deviceRefreshBtn.classList.remove('spinning');

    // 下拉框
    const sel = this.els.deviceSel;
    sel.innerHTML = '';
    if (!this.devices.length) {
      const opt = document.createElement('option');
      opt.value = '';
      opt.textContent = '未发现设备';
      sel.appendChild(opt);
      this.showDevHint('没有检测到 ADB 设备。请确认设备已连接(无线调试/USB)，然后点右侧刷新。');
    } else {
      this.devices.forEach(d => {
        const opt = document.createElement('option');
        opt.value = d;
        opt.textContent = d;
        sel.appendChild(opt);
      });
      this.hideDevHint();
    }

    // 选默认设备：URL 指定 > 上次记忆 > 第一个
    let target = this.PAGE_DEVICE;
    if (!target || !this.devices.includes(target)) {
      try { target = localStorage.getItem('onmyoji:float:device') || ''; } catch (e) { target = ''; }
    }
    if (!target || !this.devices.includes(target)) {
      target = this.devices[0] || '';
    }
    sel.value = target;
    if (this.deviceFsel) this.deviceFsel.sync();   // 同步自绘下拉的显示值与选项
    this.onDeviceChange();
  },

  async refreshDevices() {
    await this.loadDevices();
    if (this.devices.length) {
      this.toastMsg('设备列表已刷新');
    } else {
      this.toastMsg('仍未检测到设备', true);
    }
  },

  showDevHint(msg) {
    this.els.devHint.textContent = msg;
    this.els.devHint.hidden = false;
  },
  hideDevHint() {
    this.els.devHint.hidden = true;
  },

  /* ==================== 设备切换 ==================== */
  async onDeviceChange() {
    this.device = this.els.deviceSel.value || '';
    try { localStorage.setItem('onmyoji:float:device', this.device); } catch (e) {}
    // 完整页多行日志：切换设备时清掉上一台的日志，避免串台
    if (!this.isFloatEnv && this.els.lastLog) this.els.lastLog.innerHTML = '';

    // 记忆当前任务（按设备）
    this.activeTask = '';
    if (this.device) {
      try {
        const prev = localStorage.getItem(`onmyoji:float:task:${this.device}`) || '';
        if (prev) this.activeTask = prev;
      } catch (e) {}
    }

    await this.fetchLastConfigs();

    // 重建任务列表；若面板还没抓回来则补拉一次
    if (!this.TASK_META.length) this.loadTaskSchemas();
    this.rebuildTasks();

    // 立即查询一次状态
    await this.checkStatus();
  },

  /* ==================== 最近配置 ==================== */
  async fetchLastConfigs() {
    this.lastConfigs = {};
    if (!this.device) return;
    try {
      const r = await fetch(`/api/task_last_configs?device=${encodeURIComponent(this.device)}`, { cache: 'no-store' });
      const data = await r.json();
      if (data && data.success) this.lastConfigs = data.configs || {};
    } catch (e) {
      this.lastConfigs = {};
    }
  },

  /* ==================== 任务来源：/home 任务面板 ==================== */
  async loadTaskSchemas() {
    if (this._metaLoading) return;   // 防并发重复抓取
    this._metaLoading = true;
    try {
      // /home 校验设备只需任意非空字符串；面板内容与设备无关，抓到一次即可
      for (let attempt = 1; attempt <= 3; attempt++) {
        try {
          const dev = this.device || this.devices[0] || 'home';
          const r = await fetch('/home?device=' + encodeURIComponent(dev), { cache: 'no-store' });
          if (!r.ok) throw new Error('home http ' + r.status);
          const meta = this.parseHomePanels(await r.text());
          if (meta.length) {
            this.TASK_META = meta;
            this.rebuildTasks();   // 与 onDeviceChange 结果幂等，可重复调用
            return;
          }
        } catch (e) {
          // 服务刚起/页面竞态，短暂后重试
        }
        await new Promise(res => setTimeout(res, 1500));
      }
      this.toastMsg('任务面板加载失败，请稍后刷新', true);
    } finally {
      this._metaLoading = false;
    }
  },

  /** 解析 /home 页的 .task-panel → [{task,label,fields}] */
  parseHomePanels(html) {
    const doc = new DOMParser().parseFromString(html, 'text/html');
    const out = [];
    doc.querySelectorAll('#contentMain .task-panel').forEach(panel => {
      const task = (panel.id || '').replace(/^panel-/, '');
      const fields = [];
      // label[for] 直接定位同组控件（home 面板各 label 的 for 均已指向存在的控件 id）
      panel.querySelectorAll('.form-group label.form-label[for]').forEach(lbl => {
        const group = lbl.closest('.form-group');
        if (!group) return;
        let ctl = null;
        try { ctl = group.querySelector('#' + CSS.escape(lbl.htmlFor)); } catch (e) { ctl = null; }
        if (!ctl || (ctl.tagName !== 'SELECT' && ctl.tagName !== 'INPUT') || ctl.type === 'hidden') return;

        const field = {
          name: ctl.name || ctl.id,
          label: (lbl.textContent || '').trim(),
          kind: ctl.tagName === 'SELECT' ? 'select' : 'number'
        };
        // 拆「挑战次数(0为不限)」→ label=挑战次数, note=0为不限
        const mm = field.label.match(/^(.*?)[（(](.*)[)）]$/);
        if (mm) { field.label = mm[1].trim(); field.note = mm[2].trim(); }
        if (ctl.hasAttribute('min')) field.min = Number(ctl.getAttribute('min'));
        if (ctl.hasAttribute('max')) field.max = Number(ctl.getAttribute('max'));
        if (ctl.hasAttribute('step')) field.step = Number(ctl.getAttribute('step'));

        if (ctl.tagName === 'SELECT') {
          field.options = Array.from(ctl.options).map(o => [o.value, (o.textContent || '').trim()]);
        } else {
          // 非数字输入（无 min/max）按文本处理；type="float" 之类的旧写法会落到 text
          if (ctl.type !== 'number' && !ctl.hasAttribute('min') && !ctl.hasAttribute('max')) field.kind = 'text';
        }
        field.def = ctl.value;

        // 联动（同 /home 面板的 data-from / data-when）
        if (ctl.dataset.from && ctl.dataset.when) {
          try { field.depends = { on: ctl.dataset.from, when: JSON.parse(ctl.dataset.when) }; } catch (e) {}
        }
        // 「更多日志(mode)」不在小窗展示：启动时沿用最近一次配置
        if (field.name === 'mode') return;
        fields.push(field);
      });
      if (fields.length) out.push({ task, label: panel.dataset.name || task, fields });
    });
    return out;
  },

  /* ==================== 任务列表 ==================== */
  rebuildTasks() {
    this.tasks = this.TASK_META.map(m => m.task);
    // 展示 /home 面板任务；自定义任务不在此列（需到完整控制台 /home 编辑运行）

    this.renderChips();

    // 保持上次选中的任务仍存在，否则回退第一个；schema 尚未就绪时不覆盖记忆值
    if (this.tasks.length && (!this.activeTask || !this.tasks.includes(this.activeTask))) {
      this.activeTask = this.tasks[0] || '';
    }
    this.selectTask(this.activeTask);
  },

  taskLabel(task) {
    if (task === this.CUSTOM_TASK) return '自定义';
    const m = this.TASK_META.find(t => t.task === task);
    return m ? m.label : task;
  },

  renderChips() {
    const row = this.els.chipRow;
    row.innerHTML = '';
    this.tasks.forEach(task => {
      const btn = document.createElement('button');
      btn.type = 'button';
      btn.className = 'chip' + (this.lastConfigs[task] ? ' recent' : '');
      btn.dataset.task = task;
      btn.textContent = this.taskLabel(task);
      btn.addEventListener('click', () => this.selectTask(task));
      row.appendChild(btn);
    });
  },

  /* ==================== 选中任务 + 表单 ==================== */
  selectTask(task) {
    if (this.busy) return;
    if (this.running) {
      this.toastMsg('当前有任务在运行，请先点击「终止」', true);
      return;
    }
    if (!this.tasks.includes(task)) return;

    this.activeTask = task;
    document.querySelectorAll('#chipRow .chip').forEach(el => {
      el.classList.toggle('active', el.dataset.task === task);
    });
    try { localStorage.setItem(`onmyoji:float:task:${this.device}`, task); } catch (e) {}

    this.renderConfig(task);
  },

  renderConfig(task) {
    const body = this.els.cfgBody;
    const hint = this.els.customHint;
    body.innerHTML = '';
    hint.hidden = true;

    if (task === this.CUSTOM_TASK) {
      // 只读提示 + 一键按最近步骤运行
      const cfg = this.lastConfigs[task] || {};
      let stepCount = 0;
      try {
        const arr = typeof cfg.steps === 'string' ? JSON.parse(cfg.steps || '[]') : (cfg.steps || []);
        stepCount = Array.isArray(arr) ? arr.length : 0;
      } catch (e) {}
      const name = (cfg.custom_task_name || '').trim() || '未命名任务';
      hint.innerHTML = `将按最近的步骤配置运行：<b>${this.escapeHtml(name)}</b>（共 ${stepCount} 步）。
        <br>修改/新增步骤请到 <a href="/home?device=${encodeURIComponent(this.device)}">完整控制台</a>。`;
      hint.hidden = false;
      return;
    }

    const meta = this.TASK_META.find(t => t.task === task);
    if (!meta) return;

    const title = document.createElement('div');
    title.className = 'cfg-title';
    title.textContent = `${meta.label}参数`;
    body.appendChild(title);

    const recent = this.lastConfigs[task] || {};

    // 组装字段 DOM，并记录引用
    this._fields = [];
    meta.fields.forEach(f => {
      const row = document.createElement('div');
      row.className = 'f-row';

      const label = document.createElement('label');
      label.className = 'f-label';
      label.textContent = f.label;
      row.appendChild(label);

      const wrap = document.createElement('div');
      wrap.className = 'f-ctl';

      const ctl = document.createElement(f.kind === 'select' ? 'select' : 'input');
      ctl.className = 'ctl';
      ctl.dataset.fname = f.name;

      if (f.kind === 'select') {
        f.options.forEach(([val, lbl]) => {
          const opt = document.createElement('option');
          opt.value = val;
          opt.textContent = lbl;
          ctl.appendChild(opt);
        });
      } else {
        ctl.type = f.kind === 'text' ? 'text' : 'number';
        if (f.min !== undefined) ctl.min = f.min;
        if (f.max !== undefined) ctl.max = f.max;
        if (f.step !== undefined) ctl.step = f.step;
      }

      // 默认值：最近配置 > schema 默认
      const recentVal = recent[f.name];
      ctl.value = (recentVal !== undefined && recentVal !== null && recentVal !== '')
        ? String(recentVal) : String(f.def);

      if (f.note) {
        const note = document.createElement('span');
        note.className = 'f-note';
        note.textContent = f.note;
        wrap.appendChild(ctl);
        wrap.appendChild(note);
      } else {
        wrap.appendChild(ctl);
      }

      row.appendChild(wrap);
      body.appendChild(row);

      // 悬浮窗里原生 select 点不开系统列表 → 改用自绘下拉（ctl 仍留在 DOM 供读写）
      if (f.kind === 'select' && this.isFloatEnv) {
        ctl.fs = buildFancySelect(ctl);
      }

      this._fields.push({ field: f, ctl });
    });

    // 依赖联动：主字段变化时过滤从字段的可选值
    this._fields.forEach(({ field, ctl }) => {
      if (field.depends) {
        const source = this._fields.find(x => x.field.name === field.depends.on);
        if (source) {
          source.ctl.addEventListener('change', () => this.applyDependency(source.ctl));
          this.applyDependency(source.ctl);
        }
      }
    });
  },

  /** 根据触发字段当前值，过滤所有依赖它的从字段 option */
  applyDependency(sourceCtl) {
    this._fields.forEach(({ field, ctl }) => {
      if (!field.depends || field.depends.on !== sourceCtl.dataset.fname) return;
      const allowed = field.depends.when[sourceCtl.value] || [];
      let current = ctl.value;
      Array.from(ctl.options).forEach(opt => {
        opt.style.display = allowed.includes(opt.value) ? '' : 'none';
      });
      // 当前选中的值不可用则切到第一个可用值
      if (!allowed.includes(current)) {
        ctl.value = allowed[0] || '';
      }
      // 同步自绘下拉（悬浮窗）中可见项与当前值
      if (ctl.fs) ctl.fs.sync();
    });
  },

  /** 收集当前任务 config（含未在面板展示的隐藏字段默认） */
  collectConfig() {
    if (this.activeTask === this.CUSTOM_TASK) {
      // 自定义任务：完整携带最近的步骤配置
      return { ...(this.lastConfigs[this.CUSTOM_TASK] || {}) };
    }

    const cfg = {};
    (this._fields || []).forEach(({ field, ctl }) => {
      cfg[field.name] = ctl.value;
    });
    // 未展示字段：日志级别沿用最近一次，默认 less
    const recent = this.lastConfigs[this.activeTask] || {};
    cfg.mode = (recent.mode !== undefined && recent.mode !== '') ? recent.mode : 'less';
    return cfg;
  },

  /* ==================== 运行 / 终止 ==================== */
  onActionClick() {
    if (this.running) {
      this.stopTask();
    } else {
      this.startTask();
    }
  },

  async startTask() {
    if (!this.device || !this.activeTask || this.busy) return;

    this.busy = true;
    this.els.actionBtn.disabled = true;
    this.els.actionLabel.textContent = '正在启动…';

    const payload = {
      task: this.activeTask,
      device: this.device,
      config: {
        ...this.collectConfig(),
        enable_match_debug: false
      }
    };

    try {
      const resp = await fetch('/api/start_task', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload)
      });
      const data = await resp.json();
      if (resp.ok && data.success) {
        this.pushLocalLog('已提交任务：' + this.taskLabel(this.activeTask), 'info');
        this.toastMsg('任务已启动');
        // 启动成功后刷新最近配置（参数已被后端记录，便于下次回填）
        await this.fetchLastConfigs();
        this.renderChips();
        document.querySelectorAll('#chipRow .chip').forEach(el => {
          el.classList.toggle('active', el.dataset.task === this.activeTask);
        });
        // 若自定义任务第一次加入列表，也无需处理（已选中即在该列表）
        await this.checkStatus();
      } else {
        const msg = (data && data.message) || '启动失败';
        this.pushLocalLog(msg, 'error');
        this.toastMsg(msg, true);
      }
    } catch (e) {
      this.pushLocalLog('请求失败: ' + e.message, 'error');
      this.toastMsg('请求失败，请检查服务是否在运行', true);
    } finally {
      this.busy = false;
      this.renderActionState();
    }
  },

  async stopTask() {
    if (!this.device || this.busy) return;

    this.busy = true;
    this.els.actionBtn.disabled = true;
    this.els.actionLabel.textContent = '正在终止…';

    try {
      const resp = await fetch('/api/stop_task', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ device: this.device })
      });
      const data = await resp.json();
      if (data && data.success) {
        this.pushLocalLog('已发送终止指令，等待任务退出…', 'warning');
        this.toastMsg('终止指令已发送');
      } else {
        this.pushLocalLog((data && data.message) || '终止失败', 'error');
        this.toastMsg((data && data.message) || '终止失败', true);
      }
    } catch (e) {
      this.toastMsg('请求失败: ' + e.message, true);
    } finally {
      this.busy = false;
      this.renderActionState();
      // 稍后轮询会刷新真实状态
    }
  },

  /* ==================== 状态轮询 ==================== */
  startPolling() {
    this.checkStatus();
    this.pollTimer = setInterval(() => this.checkStatus(), 2000);
  },

  async checkStatus() {
    if (!this.device) {
      this.setState(false, '', '未选择设备');
      return;
    }
    try {
      const resp = await fetch(`/api/task_status?device=${encodeURIComponent(this.device)}`, { cache: 'no-store' });
      const data = await resp.json();
      this.running = !!data.running;
      this.runningTaskName = data.task_name || '';
      if (this.running) {
        this.setState(true, this.runningTaskName, '任务运行中');
      } else {
        this.setState(false, '', '空闲');
      }
    } catch (e) {
      this.setState(false, '', '服务连接失败');
    }
    this.renderActionState();
  },

  setState(running, taskName, text) {
    const dot = this.els.stateDot;
    dot.className = 'statusline__dot ' + (running ? 'busy' : 'idle');
    this.els.stateText.textContent = text;

    const badge = this.els.runningTask;
    if (running && taskName) {
      badge.textContent = this.taskLabel(taskName);
      badge.hidden = false;
    } else {
      badge.hidden = true;
    }

    // 运行中锁住任务切换与参数编辑
    this.setFormLocked(running);
  },

  setFormLocked(locked) {
    document.querySelectorAll('#chipRow .chip').forEach(el => {
      el.disabled = locked;
      el.style.opacity = locked ? '.55' : '';
    });
    if (this._fields) {
      this._fields.forEach(({ ctl }) => { ctl.disabled = locked; });
    }
    this.els.deviceSel.disabled = locked;
  },

  renderActionState() {
    const btn = this.els.actionBtn;
    const icon = this.els.actionIcon;

    if (!this.device) {
      btn.disabled = true;
      btn.classList.remove('stop');
      icon.innerHTML = '<svg viewBox="0 0 24 24" fill="currentColor"><polygon points="6,3 20,12 6,21"></polygon></svg>';
      this.els.actionLabel.textContent = '请选择设备';
      return;
    }

    if (this.busy) {
      btn.disabled = true;
      return;
    }

    btn.disabled = false;
    if (this.running) {
      btn.classList.add('stop');
      icon.innerHTML = '<svg viewBox="0 0 24 24" fill="currentColor"><rect x="6" y="6" width="12" height="12" rx="2"></rect></svg>';
      this.els.actionLabel.textContent = '终止任务';
    } else {
      btn.classList.remove('stop');
      icon.innerHTML = '<svg viewBox="0 0 24 24" fill="currentColor"><polygon points="6,3 20,12 6,21"></polygon></svg>';
      this.els.actionLabel.textContent = '开始运行';
    }
  },

  /* ==================== 日志 ==================== */
  connectWS() {
    try {
      this.ws = new WebSocket((location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + '/logs');
      this.ws.onmessage = (ev) => {
        try {
          const data = JSON.parse(ev.data);
          if (data.type === 'history' && Array.isArray(data.logs)) {
            if (this.isFloatEnv) {
              // 悬浮窗：只看最近一条
              for (let i = data.logs.length - 1; i >= 0; i--) {
                const l = data.logs[i];
                if (!l.source || l.source === this.device) { this.renderLog(l, true); break; }
              }
            } else {
              // 完整页：铺出该设备的历史日志，便于回看上下文
              this.els.lastLog.innerHTML = '';
              data.logs.forEach((l) => {
                if (!l.source || l.source === this.device) this.renderLog(l, false);
              });
            }
          } else if (data.message !== undefined) {
            if (!data.source || data.source === this.device) this.renderLog(data, this.isFloatEnv);
          }
        } catch (e) {}
      };
      this.ws.onclose = () => {
        // 断开后 3 秒重连
        setTimeout(() => {
          if (document.visibilityState !== 'hidden') this.connectWS();
        }, 3000);
      };
    } catch (e) {
      setTimeout(() => this.connectWS(), 5000);
    }
  },

  /**
   * 渲染一条日志。
   *  replace=true（悬浮窗）：只保留最新一条，保持小窗清爽；
   *  replace=false（完整页）：追加一行并自动滚到底部看最新。
   */
  renderLog(data, replace = false) {
    const el = this.els.lastLog;
    const d = new Date();
    const pad = n => String(n).padStart(2, '0');
    const time = `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
    const level = data.level || 'info';
    const cls = 'log-' + (['success', 'warning', 'error', 'info'].includes(level) ? level : 'info');

    const makeSpan = (tag, text) => {
      const s = document.createElement(tag);
      s.className = cls;
      s.textContent = text;
      return s;
    };

    if (replace) {
      el.innerHTML = '';
      const t = document.createElement('span');
      t.className = 'log-time';
      t.textContent = time;
      el.appendChild(t);
      el.appendChild(makeSpan('span', data.message));
      return;
    }

    // 多行流式：time + message 成行追加
    const row = document.createElement('div');
    row.className = 'log-row';
    const t = document.createElement('span');
    t.className = 'log-time';
    t.textContent = time;
    row.appendChild(t);
    row.appendChild(makeSpan('span', data.message));
    el.appendChild(row);
    el.scrollTop = el.scrollHeight;   // 始终展示最新一行
  },

  pushLocalLog(msg, level) {
    this.renderLog({ message: msg, level, source: this.device });
  },

  /* ==================== 其它 ==================== */

  /** 收起悬浮窗小窗：调 Android 侧 KaguraXBridge 桥把窗口收起、悬浮球回贴边。
   *  完整页 / 浏览器没有悬浮球可收，仅提示。 */
  collapseFloat() {
    const bridge = window.KaguraXBridge;
    if (bridge && typeof bridge.collapse === 'function') {
      bridge.collapse();
      return;
    }
    this.toastMsg('当前页面没有可收起的悬浮窗', true);
  },

  toastMsg(msg, err = false) {
    const t = this.els.toast;
    t.textContent = msg;
    t.classList.toggle('err', err);
    t.hidden = false;
    clearTimeout(this._toastTimer);
    this._toastTimer = setTimeout(() => { t.hidden = true; }, 2200);
  },

  escapeHtml(s) {
    return String(s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }
};

document.addEventListener('DOMContentLoaded', () => FloatApp.init());
