"""One-time reference-only restore into a fresh, migrated production database.

Run from repository root with COMPOSE_ENV_FILES=deploy/.env.prod, with app stopped.
Uses only Python stdlib and Docker; does not restore schema/ACLs or disable guards.
"""
import argparse
from pathlib import Path
import re
import subprocess
import tempfile

TABLES = '''data_imports geocoded_parcels spatial_grids zone_types zone_rule_sets
holidays source_availability enforcement_records parcel_report_stats report_aggregates
prediction_versions zone_rules facilities parking_lots prediction_target_grids risk_predictions'''.split()
COMPOSE = ['docker', 'compose', '-f', 'deploy/compose.prod.yaml']


def run(*args, content=None):
    return subprocess.run([*COMPOSE, *args], input=content, stdout=subprocess.PIPE,
                          check=True).stdout.decode('utf-8')


def sql(statement):
    return run('exec', '-T', 'db', 'sh', '-c',
               'exec psql -X -At -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"',
               content=statement.encode())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('dump', type=Path)
    args = parser.parse_args()
    if not args.dump.is_file():
        parser.error('dump not found')
    # Fail closed; never merge into existing reference/application data.
    for table in TABLES + ['users', 'parking_events']:
        if sql(f'SELECT EXISTS(SELECT 1 FROM public.{table});').strip() != 'f':
            raise RuntimeError(f'{table} must be empty; restore is for fresh databases only')
    run('cp', str(args.dump.resolve()), 'db:/tmp/reference.dump')
    toc = run('exec', '-T', 'db', 'pg_restore', '--list', '/tmp/reference.dump')
    entries = [line for line in toc.splitlines()
               if (match := re.search(r'TABLE DATA public (\w+) ', line))
               and match[1] in TABLES]
    if len(entries) != 16:
        raise RuntimeError('Expected exactly 16 TABLE DATA entries')
    columns = {}
    counts = {}
    with tempfile.TemporaryDirectory(prefix='parking-reference-') as directory:
        temp = Path(directory)
        (temp / 'selected.list').write_text('\n'.join(entries) + '\n', encoding='utf-8')
        run('cp', str(temp / 'selected.list'), 'db:/tmp/reference.list')
        run('exec', '-T', 'db', 'pg_restore', '--data-only', '--no-owner', '--no-privileges',
            '--use-list=/tmp/reference.list', '--file=/tmp/reference.sql', '/tmp/reference.dump')
        run('cp', 'db:/tmp/reference.sql', str(temp / 'reference.sql'))
        current = None
        with (temp / 'reference.sql').open(encoding='utf-8') as source, (temp / 'stage.sql').open('w', encoding='utf-8', newline='\n') as target:
            for line in source:
                match = re.match(r'^COPY public\.(\w+) \(([^\n]+)\) FROM stdin;\s*$', line)
                if match:
                    current = match[1]
                    if current not in TABLES:
                        raise RuntimeError('Unexpected COPY table')
                    columns[current] = [c.strip().strip('"') for c in match[2].split(',')]
                    counts[current] = 0
                    line = line.replace('COPY public.', 'COPY deploy_stage.', 1)
                elif current and line.rstrip('\n') == '\\.':
                    current = None
                elif current:
                    counts[current] += 1
                target.write(line)
        if set(columns) != set(TABLES):
            raise RuntimeError('Missing COPY data')
        for table, cols in columns.items():
            actual = sql("SELECT column_name FROM information_schema.columns WHERE table_schema='public' AND table_name='" + table + "' ORDER BY ordinal_position;").splitlines()
            if actual != cols:
                raise RuntimeError(f'COPY column mismatch: {table}')
        sql('CREATE SCHEMA deploy_stage;' + ''.join(
            f'CREATE TABLE deploy_stage.{t} AS TABLE public.{t} WITH NO DATA;' for t in TABLES))
        run('cp', str(temp / 'stage.sql'), 'db:/tmp/reference-stage.sql')
        run('exec', '-T', 'db', 'sh', '-c',
            'exec psql -X -q -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -f /tmp/reference-stage.sql')
    sql('GRANT USAGE ON SCHEMA deploy_stage TO parking_loader; GRANT SELECT ON ALL TABLES IN SCHEMA deploy_stage TO parking_loader;')
    statements = ['BEGIN;', 'SET LOCAL ROLE parking_loader;', 'SET LOCAL search_path=public;', 'SET CONSTRAINTS ALL DEFERRED;']
    for table in TABLES:
        cols = columns[table]
        quoted = ','.join('"' + c + '"' for c in cols)
        expressions = ['NULL' if (table, c) in [('prediction_versions', 'frozen_at'), ('zone_rule_sets', 'published_at')]
                       else 'false' if (table, c) == ('prediction_versions', 'active')
                       else '"' + c + '"' for c in cols]
        statements.append(f'INSERT INTO public.{table} ({quoted}) SELECT {",".join(expressions)} FROM deploy_stage.{table};')
    statements += [
        'UPDATE public.prediction_versions p SET frozen_at=s.frozen_at FROM deploy_stage.prediction_versions s WHERE p.id=s.id AND s.frozen_at IS NOT NULL;',
        'SELECT activate_prediction_version(id) FROM deploy_stage.prediction_versions WHERE active;',
        # Deliberately do not publish zone_rule_sets here: operator reviews rules last.
        'RESET ROLE;', 'SET LOCAL ROLE parking_migrator;']
    for table in ['enforcement_records', 'spatial_grids']:
        seq = table + '_id_seq'
        statements.append(f"SELECT setval('public.{seq}', GREATEST((SELECT COALESCE(max(id),0) FROM public.{table}), (SELECT last_value FROM public.{seq})), (SELECT COALESCE(max(id),0)>0 FROM public.{table}) OR (SELECT is_called FROM public.{seq}));")
    statements.append('COMMIT;')
    sql('\n'.join(statements))
    for table in TABLES:
        if int(sql(f'SELECT count(*) FROM public.{table};').strip()) != counts[table]:
            raise RuntimeError(f'Row count mismatch: {table}')
        print(f'{table}: {counts[table]}')
    sql('DROP SCHEMA deploy_stage CASCADE;')
    run('exec', '-T', 'db', 'rm', '-f', '/tmp/reference.dump', '/tmp/reference.list',
        '/tmp/reference.sql', '/tmp/reference-stage.sql')
    print('16 tables restored; sequences corrected; rule sets remain unpublished.')


if __name__ == '__main__':
    main()
