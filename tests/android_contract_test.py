#!/usr/bin/env python3
"""Static Android wiring checks, NOT a device/lifecycle test."""
import pathlib
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parents[1]
A = '{http://schemas.android.com/apk/res/android}'
m = ET.parse(ROOT / 'AndroidManifest.xml').getroot()
perms = {p.get(A+'name') for p in m.findall('uses-permission')}
required = {'android.permission.INTERNET', 'android.permission.FOREGROUND_SERVICE',
            'android.permission.FOREGROUND_SERVICE_DATA_SYNC', 'android.permission.POST_NOTIFICATIONS'}
assert required <= perms, 'missing foreground dataSync/notification permissions'
app = m.find('application')
assert app is not None, 'missing application declaration'
service = next((s for s in app.findall('service') if s.get(A+'name') == '.CheckinService'), None)
assert service is not None, 'missing CheckinService declaration'
assert service.get(A+'exported') == 'false' and service.get(A+'foregroundServiceType') == 'dataSync'
assert not app.findall('receiver'), 'A mode must not add a daily/boot scheduler'
src = ROOT / 'src/com/velpro/checkin'
read = lambda name: (src / name).read_text()
service_src = read('CheckinService.java')
main_src = read('MainActivity.java')
oauth_src = read('OAuthActivity.java')
settings_src = read('SettingsActivity.java')
store_src = read('Store.java')
run_src = read('RunCoordinator.java')
registry_src = read('SiteRegistry.java')
account_src = read('AccountStore.java')
wb_src = read('WorkBuddy.java')
trae_src = read('Trae.java')
wb_login_src = read('WbLoginActivity.java')
trae_login_src = read('TraeLoginActivity.java')
assert 'startForeground(' in service_src and 'START_NOT_STICKY' in service_src
assert 'startForegroundService(' in main_src and 'CheckinService.class' in main_src
assert 'startActivity(' not in service_src and 'startActivityForResult(' not in service_src, 'background cannot force a login popup'
assert 'MainActivity.NET' not in oauth_src, 'OAuth must own its state client independent of MainActivity'
assert 'setCookie(' in service_src and 'flush()' in service_src, 'cookie rotations need local persistence'
assert 'WorkManager' not in service_src and 'AlarmManager' not in service_src
assert 'failed |= !r.ok' not in service_src, 'final result must reflect latest confirmed retry, not historical failed attempt'

# v1.5: custom sites, still private / click-only / no scheduler.
assert m.get(A+'versionCode') == '11' and m.get(A+'versionName') == '1.0', 'display version 1.0 / code 11 missing'
version_src = read('Version.java')
repo_url = 'https://github.com/HRylink/daily-checkin-android'
assert 'NAME = "1.0"' in version_src and 'CODE = 11' in version_src
assert 'REPOSITORY_URL = "' + repo_url + '"' in version_src, 'missing unified HTTPS repository URL'
assert repo_url + '.git' not in version_src, 'browser URL must omit .git'
assert repo_url not in settings_src, 'SettingsActivity must use Version.REPOSITORY_URL only'
assert 'import android.net.Uri;' in settings_src
assert 'private void openRepository()' in settings_src, 'missing GitHub repository opener'
repo_method = settings_src.split('private void openRepository()', 1)[1].split('\n    private ', 1)[0]
assert 'new Intent(Intent.ACTION_VIEW,Uri.parse(Version.REPOSITORY_URL))' in repo_method
assert 'startActivity(' in repo_method
assert 'catch (android.content.ActivityNotFoundException e)' in repo_method, 'missing no-browser handler'
assert 'Toast.makeText(this,"无法打开 GitHub 仓库"' in repo_method
for forbidden in ('.check(', 'Balances', 'AccountStore', 'Store.', 'SiteRegistry', 'getCookie', 'refresh('):
    assert forbidden not in repo_method, 'GitHub entry must not invoke check-in or balance logic'
create = settings_src.split('protected void onCreate(', 1)[1].split('private void settingsHeader(', 1)[0]
assert 'settingRow(root,"▣","GitHub 仓库","查看源码",this::openRepository);' in create, 'missing footer GitHub row'
assert 'openRepository();' not in create, 'GitHub URL must open only on click'
assert app.get(A+'icon') == '@mipmap/ic_launcher', 'launcher icon reference missing'
assert (ROOT / 'res/mipmap-xxxhdpi/ic_launcher.png').is_file(), 'launcher assets must be pre-generated'
activity_names = {v.get(A+'name') for v in app.findall('activity')}
for extra in ('.WbLoginActivity', '.TraeLoginActivity', '.CustomLoginActivity'):
    assert extra in activity_names, 'missing private login activity: ' + extra
    act = next(v for v in app.findall('activity') if v.get(A+'name') == extra)
    assert act.get(A+'exported') == 'false', 'login activity must not be exported'

assert 'workbuddy-cn' in registry_src and 'https://copilot.tencent.com' in registry_src
assert 'TRAE_GLOBAL_HINT' in registry_src and 'Trae 国际版无每日签到活动' in registry_src
assert 'UNSUPPORTED' in registry_src and '未识别到该站点，可添加该网站并填写领取接口' in registry_src
assert '暂不支持' not in registry_src and '收录' not in registry_src
assert '该站点未配置签到方式，请到设置页添加网站并填写领取接口' in registry_src
assert 'WorkBuddy 国内版' in registry_src and 'AgentRouter' in registry_src
assert '"accounts"' in account_src and 'wb_access' in account_src and 'trae_device_key' in account_src
assert 'workbuddy_cn_access' in account_src, 'new site credentials need siteId-prefixed keys'
assert 'markInvalid' in account_src and 'load(' in account_src and 'saveWorkBuddy' in account_src and 'saveTrae' in account_src
assert 'AccountStore.isSecret' in store_src and 'putSecret' in store_src and 'gcm:' in store_src
assert 'AndroidKeyStore' in store_src and 'AES/GCM/NoPadding' in store_src
assert '"pass"' in store_src and '"ar_cookie"' in store_src, 'legacy encrypted fields must remain readable'

assert 'public static final Site AI' in wb_src and 'public static final Site CN' in wb_src
assert 'public WorkBuddy(Site site' in wb_src and 'site.platform' in wb_src and 'site.domain' in wb_src
assert 'public SiteRegistry.Verify verify(' in wb_src and 'public SiteRegistry.Verify verify(' in trae_src
assert 'public static final class Verify' in registry_src
assert 'public static RunCoordinator.Check check(' in registry_src
assert 'Plan.from' in run_src and 'interface Check' in run_src and 'SiteRegistry.check' in run_src
assert '到设置页重新登录该账号' in run_src
assert 'AccountStore.load' in service_src and 'RunCoordinator.Plan' in service_src and 'Store.kv' in service_src
assert 'setProgressListener(sink::status)' in service_src

assert 'siteId' in wb_login_src and 'WorkBuddy.Site' in wb_login_src
assert 'AccountStore.saveWorkBuddy' in wb_login_src and '.verify(' in wb_login_src
assert 'AccountStore.add' in wb_login_src and 'AccountStore.markInvalid' in wb_login_src
assert '凭据无效' in wb_login_src and '网络' in wb_login_src
assert 'AccountStore.saveTrae' in trae_login_src and '.verify(' in trae_login_src
assert 'AccountStore.add' in trae_login_src and 'AccountStore.markInvalid' in trae_login_src

assert '我的账号' in settings_src and '+ 添加账号' in settings_src and '识别' in settings_src
assert 'SiteRegistry.detect' in settings_src and 'start.setText("添加该网站")' in settings_src
assert 'dialog.dismiss(); showCustomDialog(typed);' in settings_src and 'SiteRegistry.TRAE_GLOBAL_HINT' in settings_src
assert '"accounts"' in settings_src and 'new Switch(this)' in settings_src and '启用签到' in settings_src
assert '删除' in settings_src and 'WbLoginActivity.class' in settings_src and 'TraeLoginActivity.class' in settings_src
assert 'hint' not in main_src and 'AccountStore.load' not in main_src
assert 'setup.setText' in main_src and '每日签到记录' in main_src
balance_src = read('Balances.java')
assert 'restoreAgentCookie' in balance_src and '.refresh(' in balance_src
assert 'AUTH_LOCK' in balance_src and 'AUTH_LOCK' in run_src, 'balance refresh and check-in must serialize credential rotation'
assert 'recordResult' in service_src and 'r.siteId' in service_src
assert service_src.index('synchronized (RunCoordinator.AUTH_LOCK)') < service_src.index('AccountStore.load(kv'), \
    'check-in must reload saved token snapshots after acquiring the refresh lock'
assert '_balance' in account_src and '_balance_at' in account_src
assert '/billing/meter/get-user-resource-summary' in wb_src and 'balanceStatus' in trae_src
assert 'CustomSite().check' not in balance_src and 'check77625(' not in balance_src
custom_src = read('CustomSite.java')
custom_login = read('CustomLoginActivity.java')
assert '"custom_sites"' in account_src and 'addCustom' in account_src and 'saveCustom' in account_src
assert 'endsWith("_cookie")' in account_src, 'custom cookie must go through Store secret encryption'
assert 'customCookie' in run_src and 'a.custom' in run_src and 'customSite(' in run_src
assert '"custom".equals(site.kind)' in registry_src and 'CustomSite' in registry_src
assert 'setInstanceFollowRedirects(false)' in custom_src and 'setFixedLengthStreamingMode' in custom_src
assert 'validUrl(' in custom_src and '请求已发出但结果未确认' in custom_src and '当天不再重试' in custom_src
assert 'CookieManager.getInstance().getCookie(config.url)' in custom_login, 'capture only endpoint-scope cookies'
assert 'Store.kv(this)' in custom_login and 'AccountStore.saveCustom' in custom_login
assert custom_login.index('AccountStore.saveCustom') < custom_login.index('.check(config, cookie)'), 'encrypt before verification'
assert 'AccountStore.addCustom' in custom_login and 'AccountStore.markInvalid' in custom_login
assert 'r.ok' in custom_login and 'RESULT_OK' in custom_login
assert 'CustomLoginActivity.class' in settings_src and 'CustomSite.validUrl' in settings_src
for java in src.glob('*.java'):
    body = java.read_text()
    assert 'import okhttp' not in body and 'import retrofit' not in body and 'import com.squareup' not in body, \
        'third-party dependency in ' + java.name
print('PASS Android wiring (static only): v1.9 homepage/add-site UI, unchanged read-only balances/cache and custom sites, private dataSync service, encrypted credentials, no forced UI/scheduler')
