(() => {
  const cfg = window.JIANBOX_ADMIN_CONFIG || {};
  const $ = (selector, root = document) => root.querySelector(selector);
  const $$ = (selector, root = document) => [...root.querySelectorAll(selector)];
  const state = {
    client: null,
    session: null,
    view: "overview",
    userPage: 1,
    nextUserPage: null,
    releases: new Map(),
    announcements: new Map(),
  };

  const views = {
    overview: ["OPERATIONS", "运营概览", "查看用户、版本和服务运行状态"],
    users: ["USER DIRECTORY", "用户管理", "查询账号、活跃信息并执行封禁操作"],
    releases: ["RELEASE CENTER", "版本发布", "上传 APK、配置策略并远程发布更新"],
    announcements: ["NOTICE CENTER", "运营公告", "管理维护通知、功能公告和安全提醒"],
    audit: ["AUDIT LOG", "管理审计", "查看高权限操作的完整记录"],
    settings: ["SERVICE STATUS", "服务状态", "检查 Storage、Edge Function 和安全边界"],
  };

  const actionNames = {
    ban: "封禁用户", unban: "解除封禁", delete: "删除用户",
    release_create: "创建版本", release_update: "修改版本", release_publish: "发布版本",
    release_unpublish: "撤回版本", release_delete: "删除版本",
    announcement_create: "创建公告", announcement_update: "修改公告",
    announcement_publish: "发布公告", announcement_unpublish: "撤回公告",
    announcement_delete: "删除公告",
  };

  function icons(root = document) {
    if (window.lucide) window.lucide.createIcons({ attrs: { "stroke-width": 1.8 }, root });
  }

  function esc(value) {
    return String(value ?? "").replace(/[&<>"']/g, char => ({
      "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;",
    }[char]));
  }

  function formatDate(value, includeTime = true) {
    if (!value) return "—";
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) return "—";
    return new Intl.DateTimeFormat("zh-CN", includeTime
      ? { year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit" }
      : { year: "numeric", month: "2-digit", day: "2-digit" }).format(date);
  }

  function formatSize(bytes) {
    const value = Number(bytes || 0);
    if (!value) return "—";
    const units = ["B", "KB", "MB", "GB"];
    const index = Math.min(Math.floor(Math.log(value) / Math.log(1024)), units.length - 1);
    return `${(value / (1024 ** index)).toFixed(index > 1 ? 1 : 0)} ${units[index]}`;
  }

  function toLocalInput(value) {
    if (!value) return "";
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) return "";
    const shifted = new Date(date.getTime() - date.getTimezoneOffset() * 60000);
    return shifted.toISOString().slice(0, 16);
  }

  function configured() {
    return cfg.supabaseUrl && /^https:\/\//.test(cfg.supabaseUrl)
      && cfg.supabasePublishableKey && !cfg.supabasePublishableKey.includes("replace_me");
  }

  function apiUrl(params = {}) {
    const url = new URL(`${cfg.supabaseUrl}/functions/v1/${cfg.functionName || "admin-console"}`);
    Object.entries(params).forEach(([key, value]) => {
      if (value !== undefined && value !== null && value !== "") url.searchParams.set(key, value);
    });
    return url;
  }

  async function api(params = {}, body) {
    if (!state.session?.access_token) throw new Error("登录会话已失效，请重新登录");
    const response = await fetch(apiUrl(params), {
      method: body ? "POST" : "GET",
      headers: {
        Authorization: `Bearer ${state.session.access_token}`,
        apikey: cfg.supabasePublishableKey,
        "Content-Type": "application/json",
      },
      body: body ? JSON.stringify(body) : undefined,
    });
    const data = await response.json().catch(() => ({}));
    if (!response.ok) throw new Error(data.error || `请求失败（${response.status}）`);
    return data;
  }

  function setStatus(text = "", type = "") {
    const el = $("#appStatus");
    el.textContent = text;
    el.className = `status-line compact ${type}`;
  }

  function toast(message, type = "success") {
    const region = $("#toastRegion");
    const item = document.createElement("div");
    item.className = `toast ${type === "error" ? "error" : ""}`;
    item.innerHTML = `<i data-lucide="${type === "error" ? "circle-alert" : "circle-check"}"></i><span>${esc(message)}</span>`;
    region.appendChild(item);
    icons(item);
    window.setTimeout(() => item.remove(), 3600);
  }

  async function ask(title, text, danger = true) {
    const dialog = $("#confirmDialog");
    $("#dialogTitle").textContent = title;
    $("#dialogText").textContent = text;
    const confirm = $("#dialogConfirm");
    confirm.className = danger ? "button danger-button" : "button primary";
    return new Promise(resolve => {
      const close = () => resolve(dialog.returnValue === "confirm");
      dialog.addEventListener("close", close, { once: true });
      dialog.showModal();
    });
  }

  function empty(message) {
    return `<div class="empty-card">${esc(message)}</div>`;
  }

  function auditIcon(action) {
    if (action.includes("publish")) return "send";
    if (action.includes("release")) return "package";
    if (action.includes("announcement")) return "megaphone";
    if (action === "ban") return "user-x";
    if (action === "unban") return "user-check";
    if (action.includes("delete")) return "trash-2";
    return "activity";
  }

  function renderAuditItems(logs, compact = false) {
    if (!logs?.length) return empty("暂无管理操作记录");
    return logs.map(log => {
      const title = actionNames[log.action] || log.action;
      const subject = log.target_name || (log.resource_id ? `${log.resource_type || "资源"} #${log.resource_id}` : "系统资源");
      return `<div class="${compact ? "activity-item" : "audit-item"}">
        <div class="${compact ? "activity-icon" : "audit-icon"}"><i data-lucide="${auditIcon(log.action)}"></i></div>
        <div><strong>${esc(title)}</strong><small>${esc(log.actor_name || "管理员")} · ${esc(subject)}</small></div>
        <time>${formatDate(log.created_at)}</time>
      </div>`;
    }).join("");
  }

  function applyMetrics(data) {
    const metrics = data.metrics || {};
    $("#registeredUsers").textContent = metrics.registered_users ?? "—";
    $("#active24h").textContent = metrics.active_24h ?? "—";
    $("#active30d").textContent = metrics.active_30d ?? "—";
    $("#anonymousCurrent").textContent = metrics.anonymous_current ?? "—";
    $("#releaseCount").textContent = data.release_count ?? "—";
    $("#announcementCount").textContent = data.published_announcements ?? "—";
  }

  function renderCurrentRelease(release) {
    const target = $("#currentRelease");
    if (!release) {
      target.innerHTML = empty("尚未发布正式版本，请前往版本中心创建并发布");
      return;
    }
    target.innerHTML = `<div class="release-version"><div class="version-badge">v${esc(release.version_code)}</div><div><h3>简盒 ${esc(release.version_name)}</h3><span class="sub">versionCode ${esc(release.version_code)} · 最低 ${esc(release.min_version_code)}</span></div></div>
      <div class="meta-line"><span class="chip published">已发布</span>${release.force_update ? '<span class="chip critical">强制更新</span>' : '<span class="chip info">普通更新</span>'}<span class="chip">${formatDate(release.published_at || release.created_at)}</span></div>`;
  }

  function renderDistribution(items) {
    const target = $("#versionDistribution");
    if (!items?.length) { target.innerHTML = empty("近 30 天暂无版本活跃数据"); return; }
    const maximum = Math.max(...items.map(item => Number(item.opens || 0)), 1);
    target.innerHTML = items.map(item => `<div class="bar-row"><strong>v${esc(item.version_code)}</strong><div class="bar-track"><div class="bar-fill" style="width:${Math.max(4, Number(item.opens || 0) / maximum * 100)}%"></div></div><span>${esc(item.opens)} 次</span></div>`).join("");
  }

  async function loadOverview() {
    const data = await api({ action: "overview" });
    applyMetrics(data);
    renderCurrentRelease(data.latest_release);
    renderDistribution(data.version_distribution);
    $("#recentAudit").innerHTML = renderAuditItems(data.recent_audit, true);
    icons($("[data-page='overview']"));
  }

  function renderUsers(users) {
    const rows = $("#userRows");
    if (!users.length) {
      rows.innerHTML = '<tr><td colspan="6" class="empty-state">未找到用户</td></tr>';
      return;
    }
    rows.innerHTML = users.map(user => {
      const banned = Boolean(user.banned_until && new Date(user.banned_until) > new Date());
      const status = user.is_admin ? '<span class="chip admin">管理员</span>'
        : banned ? '<span class="chip banned">已封禁</span>' : '<span class="chip ok">正常</span>';
      const actions = user.is_admin ? "—" : `<div class="row-actions"><button class="mini-button" data-user-action="${banned ? "unban" : "ban"}" data-user="${esc(user.id)}"><i data-lucide="${banned ? "user-check" : "user-x"}"></i>${banned ? "解封" : "封禁"}</button><button class="mini-button danger" data-user-action="delete" data-user="${esc(user.id)}"><i data-lucide="trash-2"></i>删除</button></div>`;
      return `<tr><td><span class="user-name">${esc(user.display_name || "未设置昵称")}</span><span class="sub">${esc(user.email)}</span></td><td><span class="uuid" title="${esc(user.id)}">${esc(user.id)}</span></td><td>${esc(user.coins)} 枚<span class="sub">签到 ${esc(user.checkin_count)} 次</span></td><td>${formatDate(user.last_seen_at)}<span class="sub">注册 ${formatDate(user.created_at)}</span></td><td>${status}</td><td>${actions}</td></tr>`;
    }).join("");
    $$('[data-user-action]', rows).forEach(button => button.addEventListener("click", () => mutateUser(button.dataset.userAction, button.dataset.user)));
    icons(rows);
  }

  async function loadUsers() {
    $("#userRows").innerHTML = '<tr><td colspan="6" class="empty-state">正在读取用户数据…</td></tr>';
    const data = await api({ action: "users", page: state.userPage, per_page: 30, q: $("#searchInput").value.trim() });
    renderUsers(data.users || []);
    state.nextUserPage = data.next_page;
    $("#pageLabel").textContent = `第 ${state.userPage} 页`;
    $("#previousPage").disabled = state.userPage <= 1;
    $("#nextPage").disabled = !state.nextUserPage;
  }

  async function mutateUser(action, userId) {
    const labels = { delete: "删除用户", ban: "封禁用户", unban: "解除封禁" };
    const copy = action === "delete" ? "账号及关联资料会被永久删除，操作无法恢复。"
      : action === "ban" ? "该用户将不能继续登录，已有短期会话可能在令牌到期前继续存在。"
      : "解除封禁后，该用户可以重新登录。";
    if (!await ask(labels[action], copy, action !== "unban")) return;
    await api({}, { action, user_id: userId });
    toast(`${labels[action]}成功`);
    await loadUsers();
  }

  function releaseStatus(release) {
    const channel = release.channel === "beta" ? '<span class="chip beta">测试版</span>' : '<span class="chip info">稳定版</span>';
    const published = release.is_published ? '<span class="chip published">已发布</span>' : '<span class="chip draft">草稿</span>';
    const force = release.force_update ? '<span class="chip critical">强制</span>' : "";
    return `${published}${channel}${force}`;
  }

  function renderReleases(releases) {
    const target = $("#releaseRows");
    state.releases = new Map(releases.map(item => [String(item.id), item]));
    if (!releases.length) { target.innerHTML = empty("暂无版本记录，点击右上角新建版本"); return; }
    target.innerHTML = releases.map(release => `<article class="release-row">
      <div class="release-title"><div class="release-glyph"><i data-lucide="package"></i></div><div><strong>简盒 ${esc(release.version_name)}</strong><small>versionCode ${esc(release.version_code)} · 最低 ${esc(release.min_version_code)}</small></div></div>
      <div class="release-cell">${releaseStatus(release)}<small>${release.published_at ? `发布 ${formatDate(release.published_at)}` : `创建 ${formatDate(release.created_at)}`}</small></div>
      <div class="release-cell hash-cell"><code title="${esc(release.sha256 || "")}">${esc(release.sha256 || "未记录 SHA-256")}</code><small>${esc(release.file_name || "外部下载地址")} · ${formatSize(release.file_size)}</small></div>
      <div class="release-cell"><code title="${esc(release.apk_url)}">${esc(release.apk_url || "未设置 APK 地址")}</code><small>${release.notes ? esc(release.notes.slice(0, 52)) : "未填写更新说明"}</small></div>
      <div class="row-actions"><button class="mini-button" data-release-edit="${release.id}"><i data-lucide="pencil"></i>编辑</button><button class="mini-button" data-release-toggle="${release.id}"><i data-lucide="${release.is_published ? "archive-restore" : "send"}"></i>${release.is_published ? "撤回" : "发布"}</button><button class="mini-button danger" data-release-delete="${release.id}"><i data-lucide="trash-2"></i></button></div>
    </article>`).join("");
    $$('[data-release-edit]', target).forEach(button => button.addEventListener("click", () => openRelease(button.dataset.releaseEdit)));
    $$('[data-release-toggle]', target).forEach(button => button.addEventListener("click", () => toggleRelease(button.dataset.releaseToggle)));
    $$('[data-release-delete]', target).forEach(button => button.addEventListener("click", () => removeRelease(button.dataset.releaseDelete)));
    icons(target);
  }

  async function loadReleases() {
    $("#releaseRows").textContent = "正在读取版本记录…";
    const data = await api({ action: "releases" });
    renderReleases(data.releases || []);
  }

  function resetUploadProgress() {
    $("#uploadProgress").classList.add("hidden");
    $("#uploadProgressBar").value = 0;
    $("#uploadProgressValue").textContent = "0%";
    $("#uploadProgressText").textContent = "准备上传…";
  }

  function openRelease(id = "") {
    const release = id ? state.releases.get(String(id)) : null;
    $("#releaseForm").reset();
    $("#releaseId").value = release?.id || "";
    $("#releaseStoragePath").value = release?.storage_path || "";
    $("#releaseFileName").value = release?.file_name || "";
    $("#releaseFileSize").value = release?.file_size || "";
    $("#releaseSha256").value = release?.sha256 || "";
    $("#releaseVersionCode").value = release?.version_code || "";
    $("#releaseVersionName").value = release?.version_name || "";
    $("#releaseMinVersion").value = release?.min_version_code || 1;
    $("#releaseChannel").value = release?.channel || "stable";
    $("#releaseForce").checked = Boolean(release?.force_update);
    $("#releaseApkUrl").value = release?.apk_url || "";
    $("#releaseNotes").value = release?.notes || "";
    $("#releaseFileLabel").textContent = release?.file_name ? `${release.file_name} · ${formatSize(release.file_size)}` : "选择 APK 文件";
    $("#releaseDialogTitle").textContent = release ? `编辑 ${release.version_name}` : "新建版本";
    resetUploadProgress();
    $("#releaseDialog").showModal();
  }

  function setUploadProgress(value, text) {
    const progress = Math.max(0, Math.min(100, Math.round(value)));
    $("#uploadProgress").classList.remove("hidden");
    $("#uploadProgressBar").value = progress;
    $("#uploadProgressValue").textContent = `${progress}%`;
    $("#uploadProgressText").textContent = text;
  }

  async function sha256(file) {
    setUploadProgress(2, "正在计算 SHA-256…");
    const digest = await crypto.subtle.digest("SHA-256", await file.arrayBuffer());
    return [...new Uint8Array(digest)].map(byte => byte.toString(16).padStart(2, "0")).join("");
  }

  function uploadWithProgress(file, path) {
    const encodedPath = path.split("/").map(encodeURIComponent).join("/");
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      xhr.open("POST", `${cfg.supabaseUrl}/storage/v1/object/app-releases/${encodedPath}`);
      xhr.setRequestHeader("Authorization", `Bearer ${state.session.access_token}`);
      xhr.setRequestHeader("apikey", cfg.supabasePublishableKey);
      xhr.setRequestHeader("Content-Type", file.type || "application/vnd.android.package-archive");
      xhr.setRequestHeader("x-upsert", "false");
      xhr.upload.onprogress = event => {
        if (event.lengthComputable) setUploadProgress(10 + event.loaded / event.total * 88, "正在上传 APK…");
      };
      xhr.onerror = () => reject(new Error("APK 上传网络连接失败"));
      xhr.onload = () => {
        if (xhr.status >= 200 && xhr.status < 300) resolve();
        else {
          let message = `APK 上传失败（${xhr.status}）`;
          try { message = JSON.parse(xhr.responseText).message || message; } catch { /* ignore invalid error body */ }
          reject(new Error(message));
        }
      };
      xhr.send(file);
    });
  }

  async function uploadReleaseFile(file, versionCode, channel) {
    if (!file.name.toLowerCase().endsWith(".apk")) throw new Error("请选择 APK 文件");
    if (file.size > 200 * 1024 * 1024) throw new Error("APK 文件不能超过 200 MB");
    const hash = await sha256(file);
    const safeName = file.name.replace(/[^a-zA-Z0-9._-]/g, "-").replace(/-+/g, "-");
    const path = `${channel}/v${versionCode}/${Date.now()}-${safeName}`;
    await uploadWithProgress(file, path);
    setUploadProgress(100, "APK 上传完成");
    const publicUrl = state.client.storage.from("app-releases").getPublicUrl(path).data.publicUrl;
    return { path, hash, publicUrl, fileName: file.name, fileSize: file.size };
  }

  async function saveRelease(event) {
    event.preventDefault();
    const button = $("#saveReleaseButton");
    const oldPath = $("#releaseStoragePath").value;
    let uploaded = null;
    button.disabled = true;
    try {
      const versionCode = Number($("#releaseVersionCode").value);
      const channel = $("#releaseChannel").value;
      const file = $("#releaseFile").files[0];
      if (file) uploaded = await uploadReleaseFile(file, versionCode, channel);
      const payload = {
        action: "release_save",
        id: $("#releaseId").value || undefined,
        version_code: versionCode,
        version_name: $("#releaseVersionName").value.trim(),
        min_version_code: Number($("#releaseMinVersion").value),
        channel,
        force_update: $("#releaseForce").checked,
        rollout_percent: 100,
        apk_url: uploaded?.publicUrl || $("#releaseApkUrl").value.trim(),
        notes: $("#releaseNotes").value.trim(),
        storage_path: uploaded?.path || oldPath || undefined,
        file_name: uploaded?.fileName || $("#releaseFileName").value || undefined,
        file_size: uploaded?.fileSize || Number($("#releaseFileSize").value) || undefined,
        sha256: uploaded?.hash || $("#releaseSha256").value || undefined,
      };
      await api({}, payload);
      if (uploaded && oldPath && oldPath !== uploaded.path) {
        const cleanup = await state.client.storage.from("app-releases").remove([oldPath]);
        if (cleanup.error) toast("新文件已保存，但旧 APK 清理失败", "error");
      }
      $("#releaseDialog").close();
      toast("版本草稿已保存");
      await loadReleases();
      await loadOverview();
    } catch (error) {
      if (uploaded?.path) await state.client.storage.from("app-releases").remove([uploaded.path]);
      toast(error.message, "error");
    } finally {
      button.disabled = false;
    }
  }

  async function toggleRelease(id) {
    const release = state.releases.get(String(id));
    if (!release) return;
    const publish = !release.is_published;
    const copy = publish
      ? `发布后，符合条件的简盒客户端会在启动或返回前台时检测到 ${release.version_name}。`
      : "撤回后，新发起的更新检查将不再看到这个版本。";
    if (!await ask(publish ? "发布版本" : "撤回版本", copy, !publish)) return;
    await api({}, { action: publish ? "release_publish" : "release_unpublish", id: Number(id) });
    toast(publish ? "版本已发布" : "版本已撤回");
    await Promise.all([loadReleases(), loadOverview()]);
  }

  async function removeRelease(id) {
    const release = state.releases.get(String(id));
    if (!release || !await ask("删除版本", `将删除 ${release.version_name} 的记录及其托管 APK，操作无法恢复。`)) return;
    await api({}, { action: "release_delete", id: Number(id) });
    toast("版本记录已删除");
    await Promise.all([loadReleases(), loadOverview()]);
  }

  function announcementLevel(level) {
    return { info: "普通通知", success: "功能上线", warning: "维护提醒", critical: "重要通知" }[level] || "普通通知";
  }

  function renderAnnouncements(items) {
    const target = $("#announcementRows");
    state.announcements = new Map(items.map(item => [String(item.id), item]));
    if (!items.length) { target.innerHTML = empty("暂无公告，点击右上角新建公告"); return; }
    target.innerHTML = items.map(item => `<article class="announcement-row">
      <div class="announcement-main"><strong>${esc(item.title)}</strong><p>${esc(item.body)}</p></div>
      <div class="announcement-meta"><span class="chip ${esc(item.level)}">${announcementLevel(item.level)}</span><small>${item.is_published ? '<span class="chip published">已发布</span>' : '<span class="chip draft">草稿</span>'}</small></div>
      <div class="announcement-meta"><strong>${item.starts_at ? formatDate(item.starts_at) : "立即生效"}</strong><small>${item.ends_at ? `至 ${formatDate(item.ends_at)}` : "长期有效"}</small></div>
      <div class="row-actions"><button class="mini-button" data-announcement-edit="${item.id}"><i data-lucide="pencil"></i>编辑</button><button class="mini-button" data-announcement-toggle="${item.id}"><i data-lucide="${item.is_published ? "archive-restore" : "send"}"></i>${item.is_published ? "撤回" : "发布"}</button><button class="mini-button danger" data-announcement-delete="${item.id}"><i data-lucide="trash-2"></i></button></div>
    </article>`).join("");
    $$('[data-announcement-edit]', target).forEach(button => button.addEventListener("click", () => openAnnouncement(button.dataset.announcementEdit)));
    $$('[data-announcement-toggle]', target).forEach(button => button.addEventListener("click", () => toggleAnnouncement(button.dataset.announcementToggle)));
    $$('[data-announcement-delete]', target).forEach(button => button.addEventListener("click", () => removeAnnouncement(button.dataset.announcementDelete)));
    icons(target);
  }

  async function loadAnnouncements() {
    $("#announcementRows").textContent = "正在读取公告…";
    const data = await api({ action: "announcements" });
    renderAnnouncements(data.announcements || []);
  }

  function openAnnouncement(id = "") {
    const item = id ? state.announcements.get(String(id)) : null;
    $("#announcementForm").reset();
    $("#announcementId").value = item?.id || "";
    $("#announcementTitle").value = item?.title || "";
    $("#announcementBody").value = item?.body || "";
    $("#announcementLevel").value = item?.level || "info";
    $("#announcementStartsAt").value = toLocalInput(item?.starts_at);
    $("#announcementEndsAt").value = toLocalInput(item?.ends_at);
    $("#announcementMinVersion").value = item?.target_min_version || "";
    $("#announcementMaxVersion").value = item?.target_max_version || "";
    $("#announcementDialogTitle").textContent = item ? "编辑公告" : "新建公告";
    $("#announcementDialog").showModal();
  }

  async function saveAnnouncement(event) {
    event.preventDefault();
    const button = $("#saveAnnouncementButton");
    button.disabled = true;
    try {
      await api({}, {
        action: "announcement_save",
        id: $("#announcementId").value || undefined,
        title: $("#announcementTitle").value.trim(),
        body: $("#announcementBody").value.trim(),
        level: $("#announcementLevel").value,
        starts_at: $("#announcementStartsAt").value || undefined,
        ends_at: $("#announcementEndsAt").value || undefined,
        target_min_version: $("#announcementMinVersion").value || undefined,
        target_max_version: $("#announcementMaxVersion").value || undefined,
      });
      $("#announcementDialog").close();
      toast("公告已保存");
      await loadAnnouncements();
    } catch (error) { toast(error.message, "error"); }
    finally { button.disabled = false; }
  }

  async function toggleAnnouncement(id) {
    const item = state.announcements.get(String(id));
    if (!item) return;
    const publish = !item.is_published;
    if (!await ask(publish ? "发布公告" : "撤回公告", publish ? "发布后，符合时间和版本条件的客户端可以读取该公告。" : "撤回后客户端将不再读取该公告。", !publish)) return;
    await api({}, { action: publish ? "announcement_publish" : "announcement_unpublish", id: Number(id) });
    toast(publish ? "公告已发布" : "公告已撤回");
    await Promise.all([loadAnnouncements(), loadOverview()]);
  }

  async function removeAnnouncement(id) {
    const item = state.announcements.get(String(id));
    if (!item || !await ask("删除公告", `将永久删除“${item.title}”，操作无法恢复。`)) return;
    await api({}, { action: "announcement_delete", id: Number(id) });
    toast("公告已删除");
    await loadAnnouncements();
  }

  async function loadAudit() {
    $("#auditRows").textContent = "正在读取审计记录…";
    const data = await api({ action: "audit", limit: 200 });
    $("#auditRows").innerHTML = renderAuditItems(data.logs || []);
    icons($("#auditRows"));
  }

  async function loadStatus() {
    const data = await api({ action: "status" });
    const bucket = data.release_bucket;
    $("#serviceStatus").innerHTML = `<div class="setting-row"><i data-lucide="cloud-cog"></i><div><strong>Edge Function</strong><small>${esc(data.function_name)} · 管理员 JWT 二次鉴权</small></div><span class="chip ok">在线</span></div>
      <div class="setting-row"><i data-lucide="hard-drive-upload"></i><div><strong>APK Storage</strong><small>${bucket ? `${esc(bucket.id)} · 上限 ${formatSize(bucket.file_size_limit)}` : "尚未创建 bucket"}</small></div><span class="chip ${bucket ? "ok" : "critical"}">${bucket ? (bucket.public ? "公开下载" : "私有") : "异常"}</span></div>
      <div class="setting-row"><i data-lucide="shield-check"></i><div><strong>上传权限</strong><small>仅 profiles.is_admin = true 的登录账号</small></div><span class="chip ok">RLS</span></div>
      <div class="setting-row"><i data-lucide="clock-3"></i><div><strong>最近检查</strong><small>${formatDate(data.checked_at)}</small></div><span class="chip info">实时</span></div>`;
    icons($("#serviceStatus"));
  }

  async function loadCurrentView() {
    switch (state.view) {
      case "overview": return loadOverview();
      case "users": return loadUsers();
      case "releases": return loadReleases();
      case "announcements": return loadAnnouncements();
      case "audit": return loadAudit();
      case "settings": return loadStatus();
    }
  }

  async function navigate(view) {
    if (!views[view]) return;
    state.view = view;
    $$(".nav-item").forEach(item => item.classList.toggle("active", item.dataset.view === view));
    $$(".page-view").forEach(page => page.classList.toggle("active", page.dataset.page === view));
    $("#pageKicker").textContent = views[view][0];
    $("#pageTitle").textContent = views[view][1];
    $("#pageSubtitle").textContent = views[view][2];
    setStatus("正在加载…");
    try {
      await loadCurrentView();
      setStatus(`已更新 · ${new Date().toLocaleTimeString("zh-CN", { hour: "2-digit", minute: "2-digit" })}`, "success");
    } catch (error) {
      setStatus(error.message, "error");
      toast(error.message, "error");
      throw error;
    }
  }

  async function showDashboard(session) {
    state.session = session;
    $("#adminEmail").textContent = session.user?.email || "管理员";
    $("#loginView").classList.add("hidden");
    $("#dashboardView").classList.remove("hidden");
    icons($("#dashboardView"));
    await navigate("overview");
  }

  async function logout(message = "已安全退出。") {
    await state.client.auth.signOut();
    state.session = null;
    $("#dashboardView").classList.add("hidden");
    $("#loginView").classList.remove("hidden");
    $("#loginStatus").textContent = message;
    $("#loginStatus").className = "status-line";
  }

  function bindEvents() {
    $$(".nav-item").forEach(button => button.addEventListener("click", () => navigate(button.dataset.view).catch(() => {})));
    $$('[data-go]').forEach(button => button.addEventListener("click", () => navigate(button.dataset.go).catch(() => {})));
    $$('[data-close]').forEach(button => button.addEventListener("click", () => $(`#${button.dataset.close}`).close()));
    $("#refreshButton").addEventListener("click", () => navigate(state.view).catch(() => {}));
    $("#logoutButton").addEventListener("click", () => logout());
    $("#searchButton").addEventListener("click", () => { state.userPage = 1; loadUsers().catch(error => toast(error.message, "error")); });
    $("#searchInput").addEventListener("keydown", event => {
      if (event.key === "Enter") { event.preventDefault(); state.userPage = 1; loadUsers().catch(error => toast(error.message, "error")); }
    });
    $("#previousPage").addEventListener("click", () => { if (state.userPage > 1) { state.userPage--; loadUsers().catch(error => toast(error.message, "error")); } });
    $("#nextPage").addEventListener("click", () => { if (state.nextUserPage) { state.userPage = state.nextUserPage; loadUsers().catch(error => toast(error.message, "error")); } });
    $("#newReleaseButton").addEventListener("click", () => openRelease());
    $("#releaseForm").addEventListener("submit", saveRelease);
    $("#releaseFile").addEventListener("change", event => {
      const file = event.target.files[0];
      $("#releaseFileLabel").textContent = file ? `${file.name} · ${formatSize(file.size)}` : "选择 APK 文件";
      resetUploadProgress();
    });
    $("#newAnnouncementButton").addEventListener("click", () => openAnnouncement());
    $("#announcementForm").addEventListener("submit", saveAnnouncement);
  }

  async function boot() {
    icons();
    if (!configured()) {
      $("#loginStatus").textContent = "缺少 config.js：请填写项目 URL 与 publishable key。";
      $("#loginStatus").className = "status-line error";
      return;
    }
    state.client = window.supabase.createClient(cfg.supabaseUrl, cfg.supabasePublishableKey, {
      auth: { persistSession: true, autoRefreshToken: true },
    });
    bindEvents();
    $("#loginForm").addEventListener("submit", async event => {
      event.preventDefault();
      const status = $("#loginStatus");
      status.textContent = "正在验证管理员身份…";
      status.className = "status-line";
      const result = await state.client.auth.signInWithPassword({
        email: $("#email").value.trim(),
        password: $("#password").value,
      });
      if (result.error) {
        status.textContent = result.error.message;
        status.className = "status-line error";
        return;
      }
      try { await showDashboard(result.data.session); }
      catch {
        await logout("该账号没有管理权限，或后台服务暂不可用。");
        $("#loginStatus").className = "status-line error";
      }
    });
    const result = await state.client.auth.getSession();
    if (result.data.session) {
      try { await showDashboard(result.data.session); }
      catch { await logout("登录会话无管理权限，请使用管理员账号。"); }
    }
  }

  boot().catch(error => {
    $("#loginStatus").textContent = error.message;
    $("#loginStatus").className = "status-line error";
  });
})();
