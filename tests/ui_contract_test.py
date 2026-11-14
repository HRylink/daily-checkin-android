#!/usr/bin/env python3
"""Structural UI wiring only; runtime geometry/navigation is checked on emulator separately."""
import xml.etree.ElementTree as ET  # nosec B405: trusted, repository-owned manifest
from pathlib import Path

root = Path(__file__).resolve().parents[1]
src = root / 'src/com/velpro/checkin'
a = '{http://schemas.android.com/apk/res/android}'
manifest = ET.parse(root / 'AndroidManifest.xml').getroot()  # nosec B314: local build input, not user XML
assert manifest is not None, 'empty manifest'
app = manifest.find('application')
assert app is not None, 'missing application'
activities = {v.get(a + 'name'): v for v in app.findall('activity')}
for name in ('.HistoryActivity', '.SettingsActivity', '.WbLoginActivity', '.TraeLoginActivity', '.CustomLoginActivity'):
    assert name in activities and activities[name].get(a + 'exported') == 'false', 'missing private page: ' + name
main = (src / 'MainActivity.java').read_text()
ui = (src / 'Ui.java').read_text()
settings = (src / 'SettingsActivity.java').read_text()
history = (src / 'HistoryActivity.java').read_text()
service = (src / 'CheckinService.java').read_text()
assert '每日签到记录' in main and 'new Intent(this,HistoryActivity.class)' in main
assert 'new Intent(this,SettingsActivity.class)' in main
assert 'GradientDrawable.OVAL' in ui and 'RippleDrawable' in ui
assert 'new FrameLayout.LayoutParams(dp(208),dp(208)' in main, 'main action must have equal width and height'
assert 'ProgressBar' in main and 'go.setEnabled(!busy)' in main
assert 'hint' not in main and 'StringBuilder sites' not in main and 'AccountStore.load' not in main, 'remove homepage site hint and dead aggregation'
assert 'setup.setText(' in main and '每日签到，后台执行中' in main, 'keep setup and existing action/status feedback'
assert '未添加的站点自动跳过' not in main, 'do not add a replacement site hint'
assert 'startForegroundService(' in main and 'CheckinService.isRunning()' in main
assert 'EditText' not in main and 'Store.recentText' not in main, 'settings/history must not crowd homepage'
assert 'Store.put(this,"pass"' in settings and 'OAuthActivity.class' in settings
assert 'save.setEnabled(!busy)' in settings and 'password.setEnabled(!busy)' in settings

# v1.4 account list and URL-driven adding, with the legacy credential forms retained.
assert '我的账号' in settings and '+ 添加账号' in settings and '识别' in settings
assert 'new EditText(this)' in settings and 'new AlertDialog.Builder(this)' in settings
assert '识别为：' in settings and '添加该网站' in settings and 'SiteRegistry.detect' in settings
assert '收录' not in settings and '暂不支持' not in settings
assert 'WbLoginActivity.class' in settings and 'TraeLoginActivity.class' in settings
assert 'new Switch(this)' in settings and '启用签到' in settings and '删除' in settings
assert '"accounts"' in settings and 'AccountStore.load' in settings
assert '"wb_access"' in settings and '"trae_device_key"' in settings, 'legacy credentials must remain deletable/migratable'
assert 'WorkBuddy 国内版' in (src / 'SiteRegistry.java').read_text()
assert 'AccountStore.load' not in main and '每日签到记录' in main, 'homepage must not aggregate a site list'
assert 'History(' in history and 'recent(100)' in history and 'finish()' in history
assert 'setProgressListener(sink::status)' in service
assert 'WorkBuddy' in service or 'WorkBuddy' in (src / 'RunCoordinator.java').read_text()
custom = (src / 'CustomLoginActivity.java').read_text()
assert '自定义站点（通用）' in settings and 'showCustomDialog' in settings
for label in ('名称', '领取接口地址', 'GET', 'POST', 'POST 请求体', '额外请求头'):
    assert label in settings, 'missing custom config field: ' + label
assert '添加时会立即领取一次以验证凭据' in settings and '添加时会立即领取一次以验证凭据' in custom
assert '我已完成登录' in custom and '重新打开' in custom and 'new WebView(this)' in custom
assert 'setAllowFileAccess(false)' in custom and 'setAllowContentAccess(false)' in custom
assert 'MIXED_CONTENT_NEVER_ALLOW' in custom and 'handler.cancel()' in custom
assert 'blocked(' in custom and 'CustomSite.validUrl' in custom and 'web.stopLoading()' in custom
assert 'button.setEnabled(false)' in custom, 'verification must block duplicate clicks'
assert 'CustomLoginActivity.class' in settings and 'a.custom.url' in settings
# v1.8: all kinds use the inventory-driven cards; no fixed account widgets.
create = settings.split('protected void onCreate(', 1)[1].split('private void settingsHeader(', 1)[0]
render = settings.split('private void renderAccounts()', 1)[1].split('private static String defaultName', 1)[0]
row = settings.split('private void accountRow(', 1)[1].split('private void showAddDialog()', 1)[0]
assert create.index('+ 添加账号') < create.index('我的账号')
assert 'fixedControls' not in settings and 'balance25' not in settings and 'balanceAR' not in settings
assert 'accountHeader(' not in create and 'renderAccounts();' in create
assert 'if (s == null) continue;' in render and 'accountRow(' in render
assert '"legacy25".equals(s.kind)' in row and '"agentrouter".equals(s.kind)' in row
assert 'credentialForm(card' in row and '"登录 / 重新授权 GitHub"' in row
assert 'user == null || password == null' in settings, 'save must be safe after the credential card is deleted'
assert 'draftUser' in render and 'draftPassword' in render, 'rebuilding cards must retain unsaved form text'
add = settings.split('private void showAddDialog()', 1)[1].split('private EditText customField', 1)[0]
assert 'TextWatcher' in add and 'afterTextChanged' in add and 'addTextChangedListener' in add
assert 'SiteRegistry.detect' in add and 'candidates.removeAllViews()' in add
assert 'SiteRegistry.all()' not in add and '已收录站点' not in add
assert 'for (SiteRegistry.Site chosen : d.sites)' in add, 'multiple matches must be selectable'
unknown = add.split('} else {', 1)[1].split('        };', 1)[0]
assert '未识别到已知站点，可直接添加该网站（填写领取接口）。' in unknown
assert 'start.setText("添加该网站")' in unknown and 'start.setEnabled(true)' in unknown, 'unknown URL must offer an enabled add action'
assert 'dialog.dismiss(); showCustomDialog(typed);' in unknown, 'unknown URL must reuse the custom configuration path'
assert 'SiteRegistry.TRAE_GLOBAL_HINT.equals(d.hint)' in unknown and 'result.setText(d.hint)' in unknown, 'keep Trae global hint rather than offering automatic support'
assert 'start.setEnabled(false)' not in unknown and 'launch(' not in unknown and '.check(' not in unknown
assert 'setNeutralButton("自定义站点（通用）"' in add and 'showCustomDialog(input.getText().toString().trim())' in add
assert 'if (CustomSite.validUrl(suggested)) url.setText(suggested);' in settings, 'HTTPS URL must prefill the custom endpoint field'
assert 'Ui.card(accountList)' in settings, 'each account needs its own card'
assert '"legacy25".equals(s.kind) || "agentrouter".equals(s.kind)' not in render, 'all kinds must render'
assert 'balanceText' in settings and '刷新余额' in settings and '重新登录' in settings
assert 'refreshBalances' in settings and 'postDelayed(this,1000)' in settings
assert 'BALANCES.refresh' in settings and 'removeCallbacks(refresh)' in settings
assert '刚刚' in (src / 'AccountStore.java').read_text()
# v1.7 design: glyph-only native controls, explicit actions, and unchanged balance rendering.
for label in ('使用说明', '保存设置', '通知设置', '签到记录', '启用签到', '✎', '🗑 删除'):
    assert label in settings, 'missing settings design element: ' + label
assert 'Ui.avatar' in settings and 'Ui.divider' in settings
assert 'setElevation' in ui and 'setTrackTintList' in ui and 'state_checked' in ui
assert 'setBalance(' in settings and 'ForegroundColorSpan(Ui.BLUE)' in settings
assert 'SpannableString(value)' in settings, 'preserve the existing balance prefix/raw text without duplication'
assert '添加账号后，可分别管理各账号的签到状态与余额信息。' in settings
assert '开启签到后，点首页圆形按钮会为该账号领取；未开启的账号自动跳过。' in settings
assert '系统将自动为该账号进行每日签到' not in settings and '联系客服' not in settings
assert 'new Intent(this,HistoryActivity.class)' in settings
assert 'POST_NOTIFICATIONS' in settings and 'requestPermissions' in settings
assert 'ACTION_APP_NOTIFICATION_SETTINGS' in settings and 'checkSelfPermission' in settings
assert 'Version.NAME' in settings and '每日签到' in settings
version = (src / 'Version.java').read_text()
repo_url = 'https://github.com/HRylink/daily-checkin-android'
assert 'NAME = "1.0"' in version and 'CODE = 11' in version
assert 'REPOSITORY_URL = "' + repo_url + '"' in version and repo_url not in settings
assert repo_url + '.git' not in version
assert manifest.get(a + 'versionName') == '1.0' and manifest.get(a + 'versionCode') == '11'
repo_row = 'settingRow(root,"▣","GitHub 仓库","查看源码",this::openRepository);'
assert repo_row in create, 'GitHub repository row must be constructed in onCreate'
assert 'openRepository();' not in create, 'GitHub URL must open only on click'
assert '版本 v" + Version.NAME' in create
assert create.index('版本 v" + Version.NAME') < create.index(repo_row) < create.index('renderAccounts();'), \
    'GitHub row must be the last settings view, after the version footer'
assert 'Intent.ACTION_VIEW' in settings and 'Uri.parse(Version.REPOSITORY_URL)' in settings
assert 'ActivityNotFoundException' in settings and '无法打开 GitHub 仓库' in settings
assert 'startActivity(new Intent(Intent.ACTION_VIEW' not in create, 'GitHub URL must open only on click'
assert 'AccountStore.saveCustom' in settings and 'editingId' in settings
assert 'name.setText(existing.name)' in settings and 'url.setText(existing.url)' in settings
assert 'body.setText(existing.body)' in settings and 'headers.setText(existing.headers)' in settings
assert 'method.setSelection' in settings
assert '"保存配置"' in settings and '"重新登录 / 验证"' in settings
assert 'Store.put(this,"ar_cookie","")' in settings and 'Store.put(this,"user","")' in settings
assert 'AccountStore.remove' in settings and 'AccountStore.setEnabled' in settings
assert 'previous.enabled' in settings, 'saving credentials must preserve an existing disabled switch'
assert 'originChanged ? ""' in settings, 'editing the destination must not leak old-origin cookies'
edit_save = settings.split('if (editingId != null) {', 1)[1].split('String id = AccountStore.nextCustomId', 1)[0]
assert 'AccountStore.saveCustom(kv,editingId' in edit_save and 'launchCustom(' not in edit_save and '.check(' not in edit_save
assert 'return;' in edit_save, 'editing must not fall through to add/login/claim'
assert edit_save.index('AccountStore.markInvalid') < edit_save.index('AccountStore.saveCustom'), \
    'invalidate before changing origin so a failed secret write cannot expose old cookies'
# Launcher PNGs are pre-generated; tests/build need no Pillow.
import struct
assert app.get(a + 'icon') == '@mipmap/ic_launcher'
for density, size in {'mdpi': 48, 'hdpi': 72, 'xhdpi': 96, 'xxhdpi': 144, 'xxxhdpi': 192}.items():
    png = (root / 'res' / ('mipmap-' + density) / 'ic_launcher.png').read_bytes()
    assert png[:8] == b'\x89PNG\r\n\x1a\n' and png[12:16] == b'IHDR'
    assert struct.unpack('>II', png[16:24]) == (size, size), 'wrong icon dimensions: ' + density
build = (root / 'build.sh').read_text()
assert '"$BT/aapt2" compile --dir res -o build/res.zip' in build and 'build/res.zip' in build.split('aapt2" link', 1)[1]
assert 'gen_icon.py' not in build and 'python' not in build.lower(), 'build must use committed PNGs, not Pillow'
icon_script = (root / 'tools/gen_icon.py').read_text()
assert '#F57C00' in icon_script and 'NotoSansCJK-Bold.ttc' in icon_script
print('PASS UI contract (static): v1.9 no homepage site hint, unknown URL add action, retained dynamic cards/credentials/auth, safe custom edit and launcher icon')
