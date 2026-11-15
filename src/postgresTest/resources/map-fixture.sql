-- Synthetic reference data only, in the attested disposable verification database.
INSERT INTO data_imports(id,source_dataset,file_name,file_hash,active) VALUES
 ('00000000-0000-0000-0000-000000000001','facilities','fixture','facility',true),
 ('00000000-0000-0000-0000-000000000002','reports','fixture','reports',true),
 ('00000000-0000-0000-0000-000000000003','old_reports','fixture','old',false);
INSERT INTO facilities(id,import_id,source_record_key,zone_type,spatial_data) VALUES
 (gen_random_uuid(),'00000000-0000-0000-0000-000000000001','h','HYDRANT',ST_SetSRID(ST_MakePoint(127.09,37.32),4326)),
 (gen_random_uuid(),'00000000-0000-0000-0000-000000000001','b','BUS_STOP',ST_SetSRID(ST_MakePoint(127.0905,37.3205),4326)),
 (gen_random_uuid(),'00000000-0000-0000-0000-000000000001','o','OTHER',ST_SetSRID(ST_MakePoint(127.0905,37.3205),4326)),
 (gen_random_uuid(),'00000000-0000-0000-0000-000000000003','inactive','HYDRANT',ST_SetSRID(ST_MakePoint(127.0905,37.3205),4326)),
 (gen_random_uuid(),'00000000-0000-0000-0000-000000000001','outside','HYDRANT',ST_SetSRID(ST_MakePoint(127.08999,37.32),4326));
INSERT INTO geocoded_parcels(id,parcel_key,status) VALUES
 ('00000000-0000-0000-0000-000000000011','one','SUCCEEDED'),
 ('00000000-0000-0000-0000-000000000012','two','SUCCEEDED');
INSERT INTO parcel_report_stats(import_id,parcel_id,location,period_start,period_end,report_count,event_group_count) VALUES
 ('00000000-0000-0000-0000-000000000002','00000000-0000-0000-0000-000000000011',ST_SetSRID(ST_MakePoint(127.0905,37.3205),4326),'2025-01-01','2025-09-25',5,3),
 ('00000000-0000-0000-0000-000000000002','00000000-0000-0000-0000-000000000012',ST_SetSRID(ST_MakePoint(127.0905,37.3205),4326),'2025-01-01','2025-09-25',7,4),
 ('00000000-0000-0000-0000-000000000003','00000000-0000-0000-0000-000000000011',ST_SetSRID(ST_MakePoint(127.0905,37.3205),4326),'2025-01-01','2025-09-25',100,100),
 ('00000000-0000-0000-0000-000000000002','00000000-0000-0000-0000-000000000011',ST_SetSRID(ST_MakePoint(127.0905,37.3205),4326),'2024-01-01','2024-09-25',200,200);
INSERT INTO spatial_grids(id,grid_x,grid_y,boundary)
SELECT 1,floor(ST_X(p)/50),floor(ST_Y(p)/50),ST_Transform(ST_MakeEnvelope(
 floor(ST_X(p)/50)*50,floor(ST_Y(p)/50)*50,
 floor(ST_X(p)/50)*50+50,floor(ST_Y(p)/50)*50+50,5186),4326)
FROM (SELECT ST_Transform(ST_SetSRID(ST_MakePoint(127.0905,37.3205),4326),5186) p) q;
INSERT INTO spatial_grids(id,grid_x,grid_y,boundary)
SELECT 2,grid_x+1,grid_y,ST_Transform(ST_Translate(ST_Transform(boundary,5186),50,0),4326)
FROM spatial_grids WHERE id=1;
INSERT INTO prediction_versions(id,model_type,model_version,training_start,training_end,target_definition,
 source_import_id,observed_end,holiday_policy,smoothing_radius_m,percentile_basis,caution_top_percent,target_cell_count)
VALUES ('00000000-0000-0000-0000-000000000021','BASELINE','fixture','2025-01-01','2025-08-31','REPORT_COUNT',
 '00000000-0000-0000-0000-000000000002','2025-09-25','AS_SUNDAY',50,'SAME_SLOT',8.125,1);
INSERT INTO prediction_target_grids VALUES ('00000000-0000-0000-0000-000000000021',1);
INSERT INTO risk_predictions(version_id,grid_id,weekday,time_slot,expected_count,smoothed_count,top_percent)
SELECT '00000000-0000-0000-0000-000000000021',1,w,t,1,1,8.125 FROM generate_series(1,7) w CROSS JOIN generate_series(0,7) t;
SELECT freeze_prediction_version('00000000-0000-0000-0000-000000000021');
SELECT activate_prediction_version('00000000-0000-0000-0000-000000000021');
INSERT INTO holidays VALUES ('2026-11-07','fixture holiday');
