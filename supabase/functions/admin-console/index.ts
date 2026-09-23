import { createClient } from "npm:@supabase/supabase-js@2.49.4";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, apikey, content-type, x-client-info",
  "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
};

type AdminContext = {
  actorId: string;
  caller: any;
  admin: any;
};

type JsonRecord = Record<string, unknown>;

function reply(body: unknown, status = 200) {
  return Response.json(body, { status, headers: corsHeaders });
}

async function requireAdmin(request: Request): Promise<AdminContext | Response> {
  const url = Deno.env.get("SUPABASE_URL");
  const anon = Deno.env.get("SUPABASE_ANON_KEY");
  const service = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  if (!url || !anon || !service) return reply({ error: "server configuration missing" }, 500);

  const token = request.headers.get("Authorization") || "";
  const caller = createClient(url, anon, { global: { headers: { Authorization: token } } });
  const { data: identity, error: identityError } = await caller.auth.getUser();
  if (identityError || !identity.user) return reply({ error: "unauthorized" }, 401);

  const admin = createClient(url, service, { auth: { autoRefreshToken: false, persistSession: false } });
  const { data: profile, error: profileError } = await admin
    .from("profiles").select("is_admin").eq("id", identity.user.id).maybeSingle();
  if (profileError || !profile?.is_admin) return reply({ error: "forbidden" }, 403);
  return { actorId: identity.user.id, caller, admin };
}

function positiveInt(value: unknown, fallback = 0) {
  const result = Number.parseInt(String(value ?? ""), 10);
  return Number.isFinite(result) && result > 0 ? result : fallback;
}

function boundedInt(value: unknown, fallback: number, min: number, max: number) {
  const result = Number.parseInt(String(value ?? ""), 10);
  return Number.isFinite(result) ? Math.max(min, Math.min(result, max)) : fallback;
}

function cleanText(value: unknown, max: number) {
  return String(value ?? "").trim().slice(0, max);
}

function nullableText(value: unknown, max: number) {
  const text = cleanText(value, max);
  return text || null;
}

function nullableDate(value: unknown) {
  const text = cleanText(value, 64);
  if (!text) return null;
  const date = new Date(text);
  return Number.isNaN(date.getTime()) ? undefined : date.toISOString();
}

function isHttpsUrl(value: string) {
  try { return new URL(value).protocol === "https:"; }
  catch { return false; }
}

async function writeAudit(
  ctx: AdminContext,
  action: string,
  resourceType: string,
  resourceId: string,
  details: JsonRecord = {},
  targetUserId: string | null = null,
) {
  const { error } = await ctx.admin.from("admin_audit_logs").insert({
    actor_id: ctx.actorId,
    target_user_id: targetUserId,
    action,
    resource_type: resourceType,
    resource_id: resourceId,
    details: { source: "admin-console", ...details },
  });
  if (error) console.error("audit insert failed", error.message);
}

async function users(ctx: AdminContext, url: URL) {
  const pageNo = boundedInt(url.searchParams.get("page"), 1, 1, 100000);
  const perPage = boundedInt(url.searchParams.get("per_page"), 30, 1, 100);
  const query = cleanText(url.searchParams.get("q"), 160).toLowerCase();
  const { data: authData, error } = await ctx.admin.auth.admin.listUsers({ page: pageNo, perPage });
  if (error) return reply({ error: error.message }, 500);

  const authUsers = authData.users || [];
  const ids = authUsers.map((user: any) => user.id);
  const { data: profiles, error: profileError } = ids.length
    ? await ctx.admin.from("profiles")
      .select("id,display_name,coins,checkin_count,last_checkin_date,last_seen_at,created_at,is_admin")
      .in("id", ids)
    : { data: [], error: null };
  if (profileError) return reply({ error: profileError.message }, 500);

  const profileById = new Map((profiles || []).map((profile: any) => [profile.id, profile]));
  const rows = authUsers.map((user: any) => {
    const profile: any = profileById.get(user.id) || {};
    return {
      id: user.id,
      email: user.email || "",
      created_at: user.created_at,
      email_confirmed_at: user.email_confirmed_at,
      banned_until: user.banned_until,
      display_name: profile.display_name || "",
      coins: profile.coins || 0,
      checkin_count: profile.checkin_count || 0,
      last_checkin_date: profile.last_checkin_date,
      last_seen_at: profile.last_seen_at,
      is_admin: Boolean(profile.is_admin),
    };
  }).filter((row: any) => !query || [row.id, row.email, row.display_name].join(" ").toLowerCase().includes(query));
  return reply({ users: rows, page: pageNo, per_page: perPage, next_page: authUsers.length === perPage ? pageNo + 1 : null });
}

async function metrics(ctx: AdminContext) {
  const { data, error } = await ctx.caller.rpc("jianbox_admin_metrics");
  if (error) return reply({ error: error.message }, 500);
  return reply({ metrics: Array.isArray(data) ? (data[0] || {}) : (data || {}) });
}

async function overview(ctx: AdminContext) {
  const since = new Date(Date.now() - 30 * 86400000).toISOString();
  const [metricResult, latestResult, releaseCount, announcementCount, eventResult, auditResult] = await Promise.all([
    ctx.caller.rpc("jianbox_admin_metrics"),
    ctx.admin.from("app_updates")
      .select("id,version_code,version_name,min_version_code,channel,force_update,published_at,created_at")
      .eq("is_published", true).eq("channel", "stable")
      .order("version_code", { ascending: false }).limit(1).maybeSingle(),
    ctx.admin.from("app_updates").select("id", { count: "exact", head: true }),
    ctx.admin.from("app_announcements").select("id", { count: "exact", head: true }).eq("is_published", true),
    ctx.admin.from("app_events").select("app_version").eq("event_name", "app_open").gte("created_at", since).limit(5000),
    ctx.admin.from("admin_audit_logs")
      .select("id,actor_id,target_user_id,action,resource_type,resource_id,details,created_at")
      .order("created_at", { ascending: false }).limit(6),
  ]);
  for (const result of [metricResult, latestResult, releaseCount, announcementCount, eventResult, auditResult]) {
    if (result.error) return reply({ error: result.error.message }, 500);
  }
  const distribution = new Map<number, number>();
  for (const event of eventResult.data || []) {
    const version = Number(event.app_version || 0);
    if (version > 0) distribution.set(version, (distribution.get(version) || 0) + 1);
  }
  const versions = [...distribution.entries()]
    .sort((a, b) => b[0] - a[0]).slice(0, 8)
    .map(([version_code, opens]) => ({ version_code, opens }));
  return reply({
    metrics: Array.isArray(metricResult.data) ? (metricResult.data[0] || {}) : (metricResult.data || {}),
    latest_release: latestResult.data || null,
    release_count: releaseCount.count || 0,
    published_announcements: announcementCount.count || 0,
    version_distribution: versions,
    recent_audit: auditResult.data || [],
  });
}

async function releases(ctx: AdminContext) {
  const { data, error } = await ctx.admin.from("app_updates")
    .select("id,version_code,version_name,min_version_code,apk_url,notes,is_published,force_update,channel,rollout_percent,file_name,file_size,storage_path,sha256,created_by,created_at,updated_at,published_at")
    .order("version_code", { ascending: false }).order("id", { ascending: false }).limit(200);
  return error ? reply({ error: error.message }, 500) : reply({ releases: data || [] });
}

async function announcements(ctx: AdminContext) {
  const { data, error } = await ctx.admin.from("app_announcements")
    .select("id,title,body,level,target_min_version,target_max_version,is_published,starts_at,ends_at,created_by,created_at,updated_at,published_at")
    .order("created_at", { ascending: false }).limit(200);
  return error ? reply({ error: error.message }, 500) : reply({ announcements: data || [] });
}

async function auditLogs(ctx: AdminContext, url: URL) {
  const limit = boundedInt(url.searchParams.get("limit"), 100, 1, 300);
  const { data, error } = await ctx.admin.from("admin_audit_logs")
    .select("id,actor_id,target_user_id,action,resource_type,resource_id,details,created_at")
    .order("created_at", { ascending: false }).limit(limit);
  if (error) return reply({ error: error.message }, 500);
  const ids = [...new Set((data || []).flatMap((row: any) => [row.actor_id, row.target_user_id]).filter(Boolean))];
  const { data: profiles, error: profileError } = ids.length
    ? await ctx.admin.from("profiles").select("id,display_name").in("id", ids)
    : { data: [], error: null };
  if (profileError) return reply({ error: profileError.message }, 500);
  const names = new Map((profiles || []).map((profile: any) => [profile.id, profile.display_name || "未设置昵称"]));
  return reply({
    logs: (data || []).map((row: any) => ({
      ...row,
      actor_name: names.get(row.actor_id) || "系统管理员",
      target_name: names.get(row.target_user_id) || "",
    })),
  });
}

async function systemStatus(ctx: AdminContext) {
  const { data: buckets, error } = await ctx.admin.storage.listBuckets();
  if (error) return reply({ error: error.message }, 500);
  const bucket = (buckets || []).find((item: any) => item.id === "app-releases");
  return reply({
    function_name: "admin-console",
    release_bucket: bucket ? {
      id: bucket.id,
      public: Boolean(bucket.public),
      file_size_limit: bucket.file_size_limit,
      allowed_mime_types: bucket.allowed_mime_types || [],
    } : null,
    checked_at: new Date().toISOString(),
  });
}

async function mutateUser(ctx: AdminContext, payload: JsonRecord) {
  const action = cleanText(payload.action, 32);
  const targetId = cleanText(payload.user_id, 64);
  if (!["ban", "unban", "delete"].includes(action) || !/^[0-9a-f-]{36}$/i.test(targetId)) {
    return reply({ error: "invalid request" }, 400);
  }
  if (targetId === ctx.actorId) return reply({ error: "cannot modify your own administrator account" }, 400);
  const { data: targetProfile } = await ctx.admin.from("profiles").select("is_admin").eq("id", targetId).maybeSingle();
  if (targetProfile?.is_admin) return reply({ error: "cannot modify another administrator" }, 400);

  let error: { message: string } | null = null;
  if (action === "delete") ({ error } = await ctx.admin.auth.admin.deleteUser(targetId));
  else ({ error } = await ctx.admin.auth.admin.updateUserById(targetId, { ban_duration: action === "ban" ? "876000h" : "none" }));
  if (error) return reply({ error: error.message }, 500);
  await writeAudit(ctx, action, "user", targetId, {}, targetId);
  return reply({ ok: true });
}

function releaseValues(payload: JsonRecord) {
  const versionCode = positiveInt(payload.version_code);
  const minVersion = positiveInt(payload.min_version_code, 1);
  const versionName = cleanText(payload.version_name, 80);
  const channel = payload.channel === "beta" ? "beta" : "stable";
  const apkUrl = cleanText(payload.apk_url, 2048);
  const sha256 = cleanText(payload.sha256, 64).toLowerCase();
  if (!versionCode || !versionName) return { error: "版本号和版本名称不能为空" };
  if (minVersion > versionCode) return { error: "最低可用版本不能高于发布版本" };
  if (apkUrl && !isHttpsUrl(apkUrl)) return { error: "APK 下载地址必须使用 HTTPS" };
  if (sha256 && !/^[a-f0-9]{64}$/.test(sha256)) return { error: "SHA-256 格式不正确" };
  return {
    value: {
      version_code: versionCode,
      version_name: versionName,
      min_version_code: minVersion,
      apk_url: apkUrl,
      notes: cleanText(payload.notes, 12000),
      force_update: Boolean(payload.force_update),
      channel,
      rollout_percent: boundedInt(payload.rollout_percent, 100, 0, 100),
      file_name: nullableText(payload.file_name, 255),
      file_size: payload.file_size == null ? null : Math.max(0, Number(payload.file_size) || 0),
      storage_path: nullableText(payload.storage_path, 1024),
      sha256: sha256 || null,
      updated_at: new Date().toISOString(),
    },
  };
}

async function saveRelease(ctx: AdminContext, payload: JsonRecord) {
  const id = positiveInt(payload.id);
  const parsed = releaseValues(payload);
  if ("error" in parsed) return reply({ error: parsed.error }, 400);
  const values: any = parsed.value;
  let result;
  let auditAction;
  if (id) {
    result = await ctx.admin.from("app_updates").update(values).eq("id", id).select().single();
    auditAction = "release_update";
  } else {
    result = await ctx.admin.from("app_updates").insert({ ...values, created_by: ctx.actorId }).select().single();
    auditAction = "release_create";
  }
  if (result.error) return reply({ error: result.error.message }, 500);
  await writeAudit(ctx, auditAction, "release", String(result.data.id), {
    version_code: result.data.version_code,
    version_name: result.data.version_name,
    channel: result.data.channel,
  });
  return reply({ ok: true, release: result.data });
}

async function setReleasePublished(ctx: AdminContext, payload: JsonRecord, published: boolean) {
  const id = positiveInt(payload.id);
  if (!id) return reply({ error: "invalid release id" }, 400);
  const { data: existing, error: readError } = await ctx.admin.from("app_updates").select("*").eq("id", id).maybeSingle();
  if (readError || !existing) return reply({ error: readError?.message || "release not found" }, 404);
  if (published) {
    if (!existing.apk_url || !isHttpsUrl(existing.apk_url)) return reply({ error: "发布前必须提供有效的 HTTPS APK 地址" }, 400);
    const { data: duplicate, error: duplicateError } = await ctx.admin.from("app_updates")
      .select("id").eq("channel", existing.channel).eq("version_code", existing.version_code)
      .eq("is_published", true).neq("id", id).limit(1);
    if (duplicateError) return reply({ error: duplicateError.message }, 500);
    if (duplicate?.length) return reply({ error: "同一渠道和版本号已有已发布记录" }, 409);
  }
  const values = {
    is_published: published,
    published_at: published ? new Date().toISOString() : null,
    updated_at: new Date().toISOString(),
  };
  const { data, error } = await ctx.admin.from("app_updates").update(values).eq("id", id).select().single();
  if (error) return reply({ error: error.message }, 500);
  await writeAudit(ctx, published ? "release_publish" : "release_unpublish", "release", String(id), {
    version_code: data.version_code,
    channel: data.channel,
  });
  return reply({ ok: true, release: data });
}

async function deleteRelease(ctx: AdminContext, payload: JsonRecord) {
  const id = positiveInt(payload.id);
  if (!id) return reply({ error: "invalid release id" }, 400);
  const { data: existing, error: readError } = await ctx.admin.from("app_updates")
    .select("id,version_code,channel,storage_path").eq("id", id).maybeSingle();
  if (readError || !existing) return reply({ error: readError?.message || "release not found" }, 404);
  const { error } = await ctx.admin.from("app_updates").delete().eq("id", id);
  if (error) return reply({ error: error.message }, 500);
  if (existing.storage_path) {
    const { error: storageError } = await ctx.admin.storage.from("app-releases").remove([existing.storage_path]);
    if (storageError) console.error("release storage cleanup failed", storageError.message);
  }
  await writeAudit(ctx, "release_delete", "release", String(id), {
    version_code: existing.version_code,
    channel: existing.channel,
  });
  return reply({ ok: true });
}

function announcementValues(payload: JsonRecord) {
  const title = cleanText(payload.title, 160);
  const body = cleanText(payload.body, 12000);
  const level = ["info", "success", "warning", "critical"].includes(String(payload.level))
    ? String(payload.level) : "info";
  const minVersion = payload.target_min_version ? positiveInt(payload.target_min_version) : null;
  const maxVersion = payload.target_max_version ? positiveInt(payload.target_max_version) : null;
  const startsAt = nullableDate(payload.starts_at);
  const endsAt = nullableDate(payload.ends_at);
  if (!title || !body) return { error: "公告标题和内容不能为空" };
  if (startsAt === undefined || endsAt === undefined) return { error: "公告时间格式不正确" };
  if (minVersion && maxVersion && minVersion > maxVersion) return { error: "公告版本范围不正确" };
  if (startsAt && endsAt && new Date(startsAt) >= new Date(endsAt)) return { error: "公告结束时间必须晚于开始时间" };
  return {
    value: {
      title,
      body,
      level,
      target_min_version: minVersion,
      target_max_version: maxVersion,
      starts_at: startsAt,
      ends_at: endsAt,
      updated_at: new Date().toISOString(),
    },
  };
}

async function saveAnnouncement(ctx: AdminContext, payload: JsonRecord) {
  const id = positiveInt(payload.id);
  const parsed = announcementValues(payload);
  if ("error" in parsed) return reply({ error: parsed.error }, 400);
  let result;
  let auditAction;
  if (id) {
    result = await ctx.admin.from("app_announcements").update(parsed.value).eq("id", id).select().single();
    auditAction = "announcement_update";
  } else {
    result = await ctx.admin.from("app_announcements")
      .insert({ ...parsed.value, created_by: ctx.actorId }).select().single();
    auditAction = "announcement_create";
  }
  if (result.error) return reply({ error: result.error.message }, 500);
  await writeAudit(ctx, auditAction, "announcement", String(result.data.id), { title: result.data.title });
  return reply({ ok: true, announcement: result.data });
}

async function setAnnouncementPublished(ctx: AdminContext, payload: JsonRecord, published: boolean) {
  const id = positiveInt(payload.id);
  if (!id) return reply({ error: "invalid announcement id" }, 400);
  const values = {
    is_published: published,
    published_at: published ? new Date().toISOString() : null,
    updated_at: new Date().toISOString(),
  };
  const { data, error } = await ctx.admin.from("app_announcements").update(values).eq("id", id).select().single();
  if (error) return reply({ error: error.message }, 500);
  await writeAudit(ctx, published ? "announcement_publish" : "announcement_unpublish", "announcement", String(id), { title: data.title });
  return reply({ ok: true, announcement: data });
}

async function deleteAnnouncement(ctx: AdminContext, payload: JsonRecord) {
  const id = positiveInt(payload.id);
  if (!id) return reply({ error: "invalid announcement id" }, 400);
  const { data: existing } = await ctx.admin.from("app_announcements").select("id,title").eq("id", id).maybeSingle();
  if (!existing) return reply({ error: "announcement not found" }, 404);
  const { error } = await ctx.admin.from("app_announcements").delete().eq("id", id);
  if (error) return reply({ error: error.message }, 500);
  await writeAudit(ctx, "announcement_delete", "announcement", String(id), { title: existing.title });
  return reply({ ok: true });
}

async function mutate(ctx: AdminContext, payload: JsonRecord) {
  switch (cleanText(payload.action, 64)) {
    case "ban":
    case "unban":
    case "delete":
      return mutateUser(ctx, payload);
    case "release_save":
      return saveRelease(ctx, payload);
    case "release_publish":
      return setReleasePublished(ctx, payload, true);
    case "release_unpublish":
      return setReleasePublished(ctx, payload, false);
    case "release_delete":
      return deleteRelease(ctx, payload);
    case "announcement_save":
      return saveAnnouncement(ctx, payload);
    case "announcement_publish":
      return setAnnouncementPublished(ctx, payload, true);
    case "announcement_unpublish":
      return setAnnouncementPublished(ctx, payload, false);
    case "announcement_delete":
      return deleteAnnouncement(ctx, payload);
    default:
      return reply({ error: "invalid action" }, 400);
  }
}

Deno.serve(async (request) => {
  if (request.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });
  const url = new URL(request.url);
  if (request.method === "GET" && !url.searchParams.has("action")) {
    return reply({
      service: "jianbox-admin-api",
      status: "ok",
      note: "Deploy supabase/admin-console to an HTTPS static host to use the management UI.",
    });
  }

  const ctx = await requireAdmin(request);
  if (ctx instanceof Response) return ctx;

  if (request.method === "GET") {
    switch (url.searchParams.get("action")) {
      case "metrics": return metrics(ctx);
      case "overview": return overview(ctx);
      case "users": return users(ctx, url);
      case "releases": return releases(ctx);
      case "announcements": return announcements(ctx);
      case "audit": return auditLogs(ctx, url);
      case "status": return systemStatus(ctx);
    }
  }
  if (request.method === "POST") {
    try { return mutate(ctx, await request.json()); }
    catch { return reply({ error: "invalid json body" }, 400); }
  }
  return reply({ error: "not found" }, 404);
});
