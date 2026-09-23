import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

// Deploy with verify_jwt=true. SUPABASE_SERVICE_ROLE_KEY remains only in the
// Edge Function environment: it is never distributed in the Android app.
Deno.serve(async (request) => {
  const url = Deno.env.get("SUPABASE_URL")!;
  const anon = Deno.env.get("SUPABASE_ANON_KEY")!;
  const service = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
  const token = request.headers.get("Authorization") || "";
  const caller = createClient(url, anon, { global: { headers: { Authorization: token } } });
  const { data: identity } = await caller.auth.getUser();
  if (!identity.user) return Response.json({ error: "unauthorized" }, { status: 401 });
  const { data: profile } = await caller.from("profiles").select("is_admin").eq("id", identity.user.id).single();
  if (!profile?.is_admin) return Response.json({ error: "forbidden" }, { status: 403 });
  const admin = createClient(url, service);
  const { data, error } = await admin.rpc("jianbox_metrics");
  return Response.json(error ? { error: error.message } : { metrics: data?.[0] ?? {} }, { status: error ? 500 : 200 });
});
