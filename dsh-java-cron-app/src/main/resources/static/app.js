const state = {
  conversations: [],
  activeConversationId: null,
  activeStream: null
};
const $ = selector => document.querySelector(selector);

function createId() {
  if (crypto.randomUUID) return crypto.randomUUID();
  return 'id-' + Math.random().toString(36).slice(2) + Date.now().toString(36);
}

function toast(message, error = false) {
  const item = document.createElement('div');
  item.textContent = message;
  if (error) item.classList.add('error');
  $('#toast').appendChild(item);
  setTimeout(() => item.remove(), 4000);
}

function escapeHtml(value) {
  return String(value).replace(/[&<>"']/g, char => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
  }[char]));
}

function renderMarkdown(text) {
  if (window.marked && window.DOMPurify) {
    return DOMPurify.sanitize(marked.parse(text));
  }
  return escapeHtml(text);
}

function setStreamStatus(text, visible = true) {
  $('#streamStatus').classList.toggle('hidden', !visible);
  $('#streamStatusText').innerHTML = `<span class="status-spinner"></span>${escapeHtml(text)}`;
}

function setBusy(busy) {
  $('#sendMessage').disabled = busy;
  $('#chatInput').disabled = busy;
  if (!busy) setStreamStatus('', false);
}

async function request(path, options = {}) {
  const response = await fetch(path, { headers: { 'Content-Type': 'application/json' }, ...options });
  const body = response.headers.get('content-type')?.includes('json') ? await response.json() : null;
  if (!response.ok) throw new Error(body?.message || `请求失败 (${response.status})`);
  return body;
}

// ---------- conversations ----------
function newConversation() {
  const conversation = { id: createId(), title: '新对话', messages: [], updatedAt: new Date().toISOString() };
  state.conversations.unshift(conversation);
  state.activeConversationId = conversation.id;
  saveConversations(); renderConversations(); renderChatMessages();
}
function saveConversations() {
  localStorage.setItem('dsh.cron.conversations', JSON.stringify(state.conversations.slice(0, 50)));
}
function loadConversations() {
  try { state.conversations = JSON.parse(localStorage.getItem('dsh.cron.conversations') || '[]'); }
  catch { state.conversations = []; }
}
function renderConversations() {
  const list = $('#conversationList');
  list.innerHTML = '';
  state.conversations.forEach(conversation => {
    const button = document.createElement('button');
    button.className = 'conv-item' + (conversation.id === state.activeConversationId ? ' active' : '');
    button.textContent = conversation.title;
    button.onclick = () => { state.activeConversationId = conversation.id; renderConversations(); renderChatMessages(); };
    list.appendChild(button);
  });
}
function renderChatMessages() {
  const container = $('#chatMessages');
  container.innerHTML = '';
  const conversation = state.conversations.find(item => item.id === state.activeConversationId);
  if (!conversation) return;
  conversation.messages.forEach(message => {
    const div = document.createElement('div');
    if (message.role === 'user') { div.className = 'msg user'; div.textContent = message.content; }
    else { div.className = 'msg assistant'; div.innerHTML = assistantHtml(message); }
    container.appendChild(div);
  });
  container.scrollTop = container.scrollHeight;
}
function assistantHtml(message) {
  let html = '';
  if (message.reasoning) html += `<div class="reasoning">${escapeHtml(message.reasoning.slice(-600))}</div>`;
  if (message.steps?.length) {
    html += '<div>' + message.steps.map((step, index) => `
      <details class="step-item">
        <summary>
          <span class="step-name">${escapeHtml(step.toolName || '工具 ' + (index + 1))}</span>
          <span class="step-state ${step.status === 'result' ? 'ok' : 'run'}">${step.status === 'result' ? '✓ 完成' : '… 执行中'}</span>
        </summary>
        <div class="step-detail">
          ${step.argsJson ? `<pre>${escapeHtml(step.argsJson)}</pre>` : ''}
          ${step.resultJson ? `<pre>${escapeHtml(String(step.resultJson).slice(0, 1200))}</pre>` : ''}
        </div>
      </details>`).join('') + '</div>';
  }
  html += renderMarkdown(message.content || (message.streaming ? '…' : '（本轮无内容返回）'));
  return html;
}

// ---------- cron panels ----------
async function loadParse() {
  const expr = $('#exprInput').value.trim();
  if (!expr) return toast('输入 cron 表达式', true);
  const [parsed, next] = await Promise.all([
    request('/api/cron/parse', { method: 'POST', body: JSON.stringify({ expr }) }),
    request('/api/cron/next', { method: 'POST', body: JSON.stringify({ expr, count: 5 }) })
  ]);
  const validation = await request('/api/cron/validate', { method: 'POST', body: JSON.stringify({ expr }) });
  $('#parseCard').innerHTML =
    `<div class="branch-row"><b style="font-family:monospace">${escapeHtml(expr)}</b>
      <span class="badge info">${escapeHtml(parsed.kind)}</span>
      <div>${escapeHtml(parsed.human)}</div></div>` +
    (validation.issues || []).map(i => `
      <div class="branch-row"><span class="badge ${i.severity === 'error' ? 'bad' : 'warn'}">${escapeHtml(i.severity)}</span>
        <div class="small">${escapeHtml(i.message)}</div></div>`).join('') +
    `<h4 style="margin:8px 0 4px;font-size:12.5px">下次 5 次触发</h4>` +
    (next.nextRuns || []).map(r => `<div class="branch-row small">${escapeHtml(r)}</div>`).join('');
}

async function loadJobs() {
  const jobs = await request('/api/cron/jobs');
  $('#jobsCard').innerHTML = `<div class="small muted">共 ${jobs.length} 个任务</div>` +
    jobs.map(j => `
      <div class="branch-row"><b>${escapeHtml(j.name)}</b>
        <span class="badge info" style="font-family:monospace">${escapeHtml(j.expr)}</span>
        ${j.owner ? `<span class="small muted">@${escapeHtml(j.owner)}</span>` : ''}
        <div class="muted small">${escapeHtml(j.description)}</div></div>`).join('') || '<div class="muted small">无任务，点"＋ 登记任务"</div>';
}

async function loadSuggest() {
  const data = await request('/api/cron/suggest', { method: 'POST' });
  $('#suggestCard').innerHTML =
    `<div class="small muted">${data.jobCount} 任务 · ${data.suggestionCount} 条建议</div>` +
    (data.suggestions || []).map(s => `
      <div class="branch-row"><span class="badge ${s.type === 'same-expr' ? 'warn' : 'info'}">${escapeHtml(s.type)}</span>
        <b>${escapeHtml(s.jobs.join(' ↔ '))}</b>
        <div class="muted small">${escapeHtml(s.suggestion)}</div></div>`).join('') || '<div class="muted small" style="margin-top:8px">✓ 无重复节奏</div>';
}

async function loadLibrary() {
  const data = await request('/api/cron/library');
  $('#libraryCard').innerHTML = Object.entries(data.patterns || {}).map(([k, v]) => `
    <div class="branch-row"><b>${escapeHtml(k)}</b>
      <span class="badge" style="font-family:monospace">${escapeHtml(v)}</span></div>`).join('');
}

async function refreshAll() {
  await Promise.all([loadJobs(), loadSuggest(), loadLibrary()].map(p => p.catch(e => toast(e.message, true))));
}

// ---------- chat ----------
async function sendMessage() {
  const input = $('#chatInput');
  const text = input.value.trim();
  if (!text) return;
  if (!state.activeConversationId) newConversation();
  const conversation = state.conversations.find(item => item.id === state.activeConversationId);
  if (conversation.title === '新对话') conversation.title = text.slice(0, 30);
  conversation.messages.push({ role: 'user', content: text });
  const pending = { id: createId(), role: 'assistant', content: '', reasoning: '', steps: [], streaming: true };
  conversation.messages.push(pending);
  input.value = '';
  saveConversations(); renderConversations(); renderChatMessages();
  setBusy(true);
  setStreamStatus('AI 正在思考');
  try {
    await streamAssistant(text, pending);
    setStreamStatus('已完成');
    setTimeout(() => setBusy(false), 450);
  } catch (error) {
    pending.content = error.name === 'AbortError' ? '**已停止生成。**' : error.message;
    setStreamStatus('执行失败');
    setTimeout(() => setBusy(false), 900);
  }
  saveConversations(); renderChatMessages();
}

async function streamAssistant(message, pending) {
  const controller = new AbortController();
  state.activeStream = controller;
  const response = await fetch('/api/assistant/stream', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ agentId: state.activeConversationId, message }),
    signal: controller.signal
  });
  if (!response.ok || !response.body) throw new Error(`请求失败 (${response.status})`);
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '', event = 'message', data = '', finished = false, renderQueued = false;
  const render = () => {
    if (renderQueued) return;
    renderQueued = true;
    setTimeout(() => { renderQueued = false; renderChatMessages(); }, 60);
  };
  while (true) {
    const { value, done } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    const lines = buffer.split('\n');
    buffer = lines.pop();
    lines.forEach(line => {
      if (line.startsWith('event:')) event = line.slice(6).trim();
      else if (line.startsWith('data:')) data += `${line.slice(5).trim()}\n`;
      else if (!line && data) {
        try {
          const payload = JSON.parse(data);
          if (event === 'chunk') pending.content += payload.content || '';
          if (event === 'chunk' && pending.content) setStreamStatus('正在回复');
          if (event === 'reasoning') pending.reasoning += payload.content || '';
          if (event === 'step_break') {
            const exists = payload.callId && pending.steps.some(item => item.callId === payload.callId);
            if (!exists) pending.steps.push({ ...payload, status: 'running' });
            setStreamStatus(`正在执行 ${payload.toolName || '工具'}`);
          }
          if (event === 'tool_result') {
            const step = [...pending.steps].reverse().find(item => item.callId === payload.callId)
              || [...pending.steps].reverse().find(item => item.status === 'running');
            if (step) Object.assign(step, payload, { status: 'result' });
            else pending.steps.push({ ...payload, status: 'result' });
            setStreamStatus('正在整理结果');
          }
          if (event === 'finish' || event === 'done') {
            pending.steps.forEach(item => { if (item.status === 'running') item.status = 'result'; });
            pending.streaming = false;
            finished = true;
          }
          if (event === 'error') pending.content += `\n\n**错误：** ${payload.message || '未知错误'}`;
          render();
        } catch { }
        event = 'message';
        data = '';
      }
    });
    if (finished) break;
  }
  if (!finished) pending.streaming = false;
  state.activeStream = null;
  renderChatMessages();
}

// ---------- events ----------
$('#sendMessage').onclick = sendMessage;
$('#chatInput').addEventListener('keydown', event => {
  if ((event.metaKey || event.ctrlKey) && event.key === 'Enter') { event.preventDefault(); sendMessage(); }
});
$('#stopStream').onclick = () => state.activeStream?.abort();
$('#newConversation').onclick = () => { newConversation(); };
$('#parseBtn').onclick = () => loadParse().catch(e => toast(e.message, true));
$('#exprInput').addEventListener('keydown', e => { if (e.key === 'Enter') $('#parseBtn').click(); });
$('#registerJob').onclick = () => $('#jobModal').classList.remove('hidden');
$('#cancelModal').onclick = () => $('#jobModal').classList.add('hidden');
$('#jobForm').onsubmit = async event => {
  event.preventDefault();
  const form = new FormData(event.target);
  try {
    await request('/api/cron/jobs', {
      method: 'POST',
      body: JSON.stringify({ name: form.get('name'), expr: form.get('expr'), owner: form.get('owner'), description: form.get('description') })
    });
    $('#jobModal').classList.add('hidden');
    event.target.reset();
    toast('任务已登记');
    await refreshAll();
  } catch (error) { toast(error.message, true); }
};
$('#refreshResource').onclick = async () => { await refreshAll(); toast('已刷新'); };
document.querySelectorAll('[data-resource-tab]').forEach(button => {
  button.onclick = () => {
    document.querySelectorAll('[data-resource-tab]').forEach(item => item.classList.toggle('active', item === button));
    document.querySelectorAll('[data-tab]').forEach(panel => panel.classList.toggle('hidden', panel.dataset.tab !== button.dataset.resourceTab));
  };
});
$('#toggleTheme').onclick = () => {
  const next = document.documentElement.dataset.theme === 'dark' ? 'light' : 'dark';
  document.documentElement.dataset.theme = next;
  localStorage.setItem('dsh.cron.theme', next);
  $('#toggleTheme').textContent = next === 'dark' ? '☀️' : '🌙';
};
document.documentElement.dataset.theme = localStorage.getItem('dsh.cron.theme') || 'light';
$('#toggleTheme').textContent = document.documentElement.dataset.theme === 'dark' ? '☀️' : '🌙';

(async function init() {
  loadConversations();
  if (!state.conversations.length) newConversation();
  else { state.activeConversationId = state.conversations[0].id; }
  renderConversations(); renderChatMessages();
  try { await refreshAll(); } catch (error) { toast(error.message, true); }
})();
