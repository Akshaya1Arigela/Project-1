-- ============================================================================
--  AeroSaga — Supabase schema
-- ============================================================================
--  Paste this whole file into the Supabase SQL Editor and press Run.
--
--  What lives here, and why:
--
--    drones           the fleet registry. The dashboard's 3D markers come from this.
--    missions         one row per Temporal workflow run - the audit trail.
--    mission_events   every phase transition, including the OFFLINE_SUSPENDED ->
--                     NAVIGATING resume. This table is your proof for the report.
--
--  IMPORTANT: Temporal does NOT use this database. Temporal keeps its own durable
--  execution state in the Postgres container started by docker-compose.yml. You
--  cannot move that here, and you should not want to: the durability is the point.
--  Supabase is the *application* database alongside it.
-- ============================================================================

create extension if not exists "pgcrypto";

-- ---------------------------------------------------------------------------
-- 1. Fleet registry
-- ---------------------------------------------------------------------------
create table if not exists public.drones (
  id           text primary key,                       -- DRN-01
  callsign     text not null,                          -- Garuda-01
  lat          double precision not null default 0,
  lng          double precision not null default 0,
  altitude_m   double precision not null default 0,
  battery_pct  double precision not null default 100 check (battery_pct between 0 and 100),
  status       text not null default 'idle'
               check (status in ('idle','charging','maintenance','on-mission')),
  home_base    text not null default 'Hyderabad Hub',
  created_at   timestamptz not null default now(),
  updated_at   timestamptz not null default now()
);

-- ---------------------------------------------------------------------------
-- 2. Mission audit trail (one row per workflow run)
-- ---------------------------------------------------------------------------
create table if not exists public.missions (
  mission_id        text primary key,
  drone_id          text references public.drones (id) on delete set null,
  workflow_id       text not null,                     -- "mission-<missionId>" in Temporal
  task_queue        text not null default 'aero-tq',
  phase             text not null default 'QUEUED',
  detail            text,
  battery_pct       double precision,
  lat               double precision,
  lng               double precision,
  payload_kg        double precision,
  destination_label text,
  started_at        timestamptz not null default now(),
  updated_at        timestamptz not null default now(),
  completed_at      timestamptz
);

-- ---------------------------------------------------------------------------
-- 3. Every state transition - this is the resiliency evidence
-- ---------------------------------------------------------------------------
create table if not exists public.mission_events (
  id         bigint generated always as identity primary key,
  mission_id text not null,
  drone_id   text,
  phase      text not null,
  detail     text,
  created_at timestamptz not null default now()
);

create index if not exists missions_phase_idx       on public.missions (phase);
create index if not exists missions_drone_idx       on public.missions (drone_id);
create index if not exists missions_started_idx     on public.missions (started_at desc);
create index if not exists mission_events_mid_idx   on public.mission_events (mission_id, created_at);

-- ---------------------------------------------------------------------------
-- 4. Row Level Security
--
--    The Java backend authenticates with the service_role key, which BYPASSES RLS.
--    The browser uses the anon key, so it can only ever read. There is no client-side
--    write path to this database at all - mutations always go through the backend.
-- ---------------------------------------------------------------------------
alter table public.drones        enable row level security;
alter table public.missions      enable row level security;
alter table public.mission_events enable row level security;

drop policy if exists "anon can read drones"        on public.drones;
drop policy if exists "anon can read missions"      on public.missions;
drop policy if exists "anon can read mission_events" on public.mission_events;

create policy "anon can read drones"
  on public.drones for select to anon, authenticated using (true);

create policy "anon can read missions"
  on public.missions for select to anon, authenticated using (true);

create policy "anon can read mission_events"
  on public.mission_events for select to anon, authenticated using (true);

-- ---------------------------------------------------------------------------
-- 5. updated_at trigger
-- ---------------------------------------------------------------------------
create or replace function public.touch_updated_at()
returns trigger language plpgsql as $$
begin
  new.updated_at = now();
  return new;
end;
$$;

drop trigger if exists drones_touch on public.drones;
create trigger drones_touch before update on public.drones
  for each row execute function public.touch_updated_at();

drop trigger if exists missions_touch on public.missions;
create trigger missions_touch before update on public.missions
  for each row execute function public.touch_updated_at();

-- ---------------------------------------------------------------------------
-- 6. Seed 50 drones around the Hyderabad hub
--
--    Re-running this is safe: ON CONFLICT DO NOTHING means your edited rows survive.
--    Delete the rows first if you want to re-randomise positions:
--      delete from public.drones;
-- ---------------------------------------------------------------------------
insert into public.drones (id, callsign, lat, lng, altitude_m, battery_pct, status, home_base)
select
  'DRN-' || lpad(n::text, 2, '0'),
  (case when n <= 25 then 'Garuda-' else 'Vayu-' end) || lpad(n::text, 2, '0'),
  round((17.3850 + (random() - 0.5) * 0.10)::numeric, 6)::double precision,
  round((78.4867 + (random() - 0.5) * 0.10)::numeric, 6)::double precision,
  0,
  round((55 + random() * 45)::numeric, 1)::double precision,
  case
    when n % 11 = 0 then 'maintenance'
    when n % 7  = 0 then 'charging'
    else 'idle'
  end,
  'Hyderabad Hub'
from generate_series(1, 50) as n
on conflict (id) do nothing;

-- ---------------------------------------------------------------------------
-- Done. Verify:
--   select count(*) from public.drones;   -- 50
--   select * from public.drones limit 5;
-- ---------------------------------------------------------------------------
