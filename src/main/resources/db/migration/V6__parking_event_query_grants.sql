-- V4 grants API reference reads through the parking_app group role.
-- Detailed parking-event peak statistics also require this single reference table.
GRANT SELECT ON public.report_aggregates TO parking_app;
