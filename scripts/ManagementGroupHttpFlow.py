from pathlib import Path
import concurrent.futures
import hashlib
import http.client
import json
import os
import socket
import subprocess
import time

import argparse
parser = argparse.ArgumentParser(description='Real Main management publication HTTP flow against fresh synthetic fixture only')
parser.add_argument('--classes', type=Path, required=True)
parser.add_argument('--fixture', type=Path, required=True)
parser.add_argument('--artifacts', type=Path, required=True)
parser.add_argument('--java', default='java')
args = parser.parse_args()
app = Path(__file__).resolve().parents[1]
stage = args.artifacts.resolve()
stage.mkdir(parents=True, exist_ok=False)
descriptor = args.fixture.resolve()
fixture = json.loads(descriptor.read_text())
assert fixture['synthetic'] is True
classes = args.classes.resolve()
assert (classes / 'com/training/OrganizationManagementGroupHttp.class').is_file()
checks, trace, child = [], [], None
log = (stage / 'full-http-server.log').open('w')
env = dict(os.environ, JAVA_TOOL_OPTIONS='', JDK_JAVA_OPTIONS='', _JAVA_OPTIONS='', YANXU_SEMANTIC_PORT='',
    YANXU_SEMANTIC_TOKEN='', YANXU_LOGIN_MAIL_TRANSPORT='disabled', YANXU_CITY_PLANNING_COLLECTION_FILE='')
props = dict(fixture['systemProperties'], **{'bind.address':'127.0.0.1','semantic.port':'','semantic.token':'','http.workers':'4'})
classpath = ':'.join(map(str, [classes, app/'lib/h2.jar', app/'lib/pdfbox-app-3.0.8.jar', app/'lib/ip2region-3.3.7.jar']))
with socket.socket() as sock:
    sock.bind(('127.0.0.1', 0)); port = sock.getsockname()[1]

def check(value, label):
    if not value: raise AssertionError(label)
    checks.append(label)

def call(route, token=None, body=None, expected=200):
    connection = http.client.HTTPConnection('127.0.0.1', port, timeout=10)
    headers = {'Content-Type':'application/json'}
    if token: headers['X-Token'] = token
    connection.request('POST' if body is not None else 'GET', '/api'+route, None if body is None else json.dumps(body), headers)
    response = connection.getresponse(); raw = response.read(); status = response.status
    parsed = json.loads(raw); connection.close()
    trace.append({'route':route,'method':'POST' if body is not None else 'GET','status':status})
    check(status == expected, f'{route} HTTP {expected} (got {status})')
    return parsed.get('data') if status == 200 else parsed

def login(kind):
    credentials = fixture['logins'][kind]
    result = call('/login', body={k:credentials[k] for k in ['username','password']})
    return result['token']

def start():
    global child
    child = subprocess.Popen([args.java, *['-D'+key+'='+str(value) for key,value in props.items()],
        '-cp',classpath,'com.training.Main',str(port)], cwd=app, env=env, stdout=log, stderr=log)
    for _ in range(100):
        if child.poll() is not None: raise RuntimeError('Synthetic Main exited; inspect local log')
        try:
            with socket.create_connection(('127.0.0.1',port), .1): return
        except OSError: time.sleep(.1)
    raise RuntimeError('Synthetic Main did not start')

def stop():
    global child
    if child is not None and child.poll() is None:
        child.terminate()
        try: child.wait(timeout=15)
        except subprocess.TimeoutExpired: child.kill(); child.wait(timeout=5)
    child = None

def request(preview):
    return {key:preview[key] for key in ['expectedVersion','snapshotFingerprint','reviewToken']} | {'reviewed':True}

def user_rows(value):
    if isinstance(value, list): return value
    return value.get('list', value.get('rows', value.get('items')))

try:
    start(); admin = login('admin'); affected = login('affected'); manager = login('manager'); viewer = login('viewer')
    users_before = user_rows(call('/users', admin)); config_before = call('/organization/config', admin)
    check(len(users_before) == fixture['counters']['users'], 'all prepared users visible')
    before = call('/organization/management-group/received', admin)
    check(before == {'published':False,'ready':False}, 'no existing publication receipt')
    first = call('/organization/management-group/preview', admin)
    check(first['ready'] and not first['published'], 'complete ten identity preview')
    check(len(first['diff']['userRoleChanges']) == 10, 'exactly ten preview role changes')
    check(first['accountsActivated'] is False and first['passwordsChanged'] is False, 'preview does not activate or set passwords')
    call('/organization/management-group/preview', viewer, expected=403)
    check(call('/users', admin) is not None, 'unrelated denied read leaves administrator valid')
    # Pinned file restoration cannot revive the old HTTP host's review.
    source = Path(fixture['supplementalConfig']['projection']['path']); saved = source.read_bytes()
    try:
        source.write_text('SYNTHETIC TAMPER')
        call('/organization/management-group/commit', admin, request(first), 503)
    finally: source.write_bytes(saved)
    call('/organization/management-group/commit', admin, request(first), 409)
    check(call('/organization/config', admin) == config_before, 'source conflict did not publish configuration')
    check(user_rows(call('/users', admin)) == users_before, 'source conflict did not change any user')
    second = call('/organization/management-group/preview', admin)
    invalid = request(second) | {'accountId':fixture['logins']['manager']['accountId']}
    call('/organization/management-group/commit', admin, invalid, 400)
    call('/organization/management-group/commit', admin, request(second), 409)
    final = call('/organization/management-group/preview', admin)
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        results = list(pool.map(lambda _: call('/organization/management-group/commit', admin, request(final)), range(2)))
    check(sum(result['replayed'] is False for result in results) == 1, 'concurrent identical confirm writes once')
    receipt = next(result for result in results if result['replayed'] is False)
    check(receipt['published'] and receipt['accountsActivated'] is False and receipt['passwordsChanged'] is False, 'published roles and config without activation/password writes')
    check(len(receipt['members']) == 10, 'durable receipt contains ten unique identities')
    ids = set(fixture['targetIds']); users_after = user_rows(call('/users', admin))
    expected = [row | ({'role':'admin'} if row['id'] in ids else {}) for row in users_before]
    check(users_after == expected, 'all visible user columns preserved except ten approved roles')
    config_after = call('/organization/config', admin)
    check(config_after['configuration'] == final['configuration'], 'exact reviewed config atomically published')
    call('/me', affected, expected=401); call('/me', manager, expected=401)
    check(call('/me', viewer)['role'] == 'viewer', 'unrelated viewer session preserved')
    manager_new = login('manager'); check(call('/me', manager_new)['role'] == 'admin', 'manager01 original password still works with updated role')
    recovered = call('/organization/management-group/received', admin)
    check(recovered['replayed'] and recovered['version'] == receipt['version'], 'new result can be read without duplicate publication')
    stop(); start(); admin = login('admin')
    restored = call('/organization/management-group/preview', admin)
    check(restored['published'] and restored['replayed'] and 'reviewToken' not in restored, 'real Main restart returns durable result without new review')
    check(restored['version'] == receipt['version'], 'restart keeps identical version')
    check(user_rows(call('/users', admin)) == users_after, 'restart creates no accounts or additional changes')
    result = {'status':'PASSED','checks':len(checks),'labels':checks,'targets':10,'synthetic':True,'shared_installed':False,'production_touched':False,
        'product_classes':str(classes),'descriptor':str(descriptor),'classes':str(classes),'same_request_successful_writes':1}
    (stage/'full-http-validation.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
    (stage/'full-http-receipt.json').write_text(json.dumps(receipt,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps({key:result[key] for key in ['status','checks','targets','synthetic','production_touched']}))
finally:
    stop(); log.close()
    (stage/'full-http-trace.json').write_text(json.dumps(trace,ensure_ascii=False,indent=2)+'\n')
