-- Read-only inspection only. Run using a separately supplied read-only login, never the isolated-test runner.
-- No baseline name/ID is selected by code: inspect the active version from this transaction snapshot.
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SELECT current_database(), current_user, version();
SELECT id,model_version,model_type,active,frozen_at,target_cell_count,loaded_row_count,
       source_import_id,caution_top_percent,holiday_policy,percentile_basis,tie_method,
       projected_srid,cell_size_m,smoothing_radius_m
FROM public.prediction_versions WHERE active;

WITH samples(label,lat,lng) AS (
  VALUES ('sample1',37.324450::float8,127.096184::float8),('sample2',37.324900,127.096749)
), projected AS (
  SELECT *,ST_Transform(ST_SetSRID(ST_MakePoint(lng,lat),4326),5186) AS point FROM samples
), calculated AS (
  SELECT *,floor(ST_X(point)/50)::int AS grid_x,floor(ST_Y(point)/50)::int AS grid_y FROM projected
)
SELECT c.label,c.grid_x,c.grid_y,g.id AS grid_id,g.in_model_area,
       v.id AS version_id,v.model_version,v.caution_top_percent,
       rp.top_percent,rp.smoothed_count,
       rp.top_percent<=v.caution_top_percent AS model_level2,
       CASE WHEN v.holiday_policy='AS_SUNDAY' AND EXISTS (
         SELECT 1 FROM public.holidays WHERE holiday_date=DATE '2026-11-07') THEN 7 ELSE 6 END AS weekday,
       6 AS time_slot
FROM calculated c
LEFT JOIN public.spatial_grids g ON g.projected_srid=5186 AND g.cell_size_m=50 AND g.grid_x=c.grid_x AND g.grid_y=c.grid_y
LEFT JOIN public.prediction_versions v ON v.active
LEFT JOIN public.risk_predictions rp ON rp.version_id=v.id AND rp.grid_id=g.id
  AND rp.time_slot=6 AND rp.weekday=CASE WHEN v.holiday_policy='AS_SUNDAY' AND EXISTS (
    SELECT 1 FROM public.holidays WHERE holiday_date=DATE '2026-11-07') THEN 7 ELSE 6 END;

-- All matching published MVP rules are displayed separately. The application must choose its configured rule_set.
SELECT r.rule_set,f.zone_type,f.id AS facility_id,f.import_id,r.legal_distance_m,
       ST_Distance(f.spatial_data,ST_SetSRID(ST_MakePoint(127.096184,37.324450),4326)::geography) AS distance_m
FROM public.facilities f JOIN public.data_imports d ON d.id=f.import_id AND d.active
JOIN public.zone_rules r ON r.zone_type=f.zone_type
JOIN public.zone_rule_sets rs ON rs.rule_set=r.rule_set AND rs.published_at IS NOT NULL
WHERE f.zone_type IN ('HYDRANT','BUS_STOP') AND r.geometry_rule='DISTANCE'
  AND ST_DWithin(f.spatial_data,ST_SetSRID(ST_MakePoint(127.096184,37.324450),4326)::geography,r.legal_distance_m)
ORDER BY r.rule_set,f.zone_type,distance_m,f.id;
SELECT source_dataset,id FROM public.data_imports WHERE active ORDER BY source_dataset;
COMMIT;
