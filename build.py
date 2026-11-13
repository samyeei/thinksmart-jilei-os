from pathlib import Path
import os
import re
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parent
SDK = Path(os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT') or Path.home() / 'Library/Android/sdk')
TOOLS = SDK / 'build-tools/36.0.0'
OUTPUT = ROOT / 'dist/jile-os-v5.apk'
GRADLE = os.environ.get('JILE_GRADLE', 'gradle')

OUTPUT.parent.mkdir(parents=True, exist_ok=True)
with tempfile.TemporaryDirectory() as name:
    work = Path(name)
    brand = ROOT / 'shared/ic_brand.png'
    res_dir = work / 'res'
    assets_dir = work / 'assets'
    shutil.copytree(ROOT / 'res', res_dir)
    shutil.copytree(ROOT / 'assets', assets_dir)
    (res_dir / 'drawable-nodpi').mkdir(exist_ok=True)
    shutil.copyfile(brand, res_dir / 'drawable-nodpi/ic_brand.png')
    shutil.copyfile(brand, assets_dir / 'parent/brand.png')
    full_assets = ROOT / 'shared'
    login_fill = (full_assets / 'site-login-fill.js').read_text().strip()
    login_port = (assets_dir / 'approved-sites/login-port.js').read_text()
    (assets_dir / 'approved-sites/login-fill.js').write_text(
        "'use strict';\nconst fillSavedLogin = " + login_fill + ";\n" + login_port)
    wallpaper_dir = assets_dir / 'parent/wallpapers'
    wallpaper_dir.mkdir()
    shutil.copyfile(res_dir / 'drawable-nodpi/desktop_wallpaper.webp', wallpaper_dir / 'original.webp')
    for name in ('aurora', 'cosmos', 'dune'):
        source = full_assets / f'web/assets/{name}.webp'
        shutil.copyfile(source, wallpaper_dir / f'{name}.webp')
        shutil.copyfile(source, res_dir / f'drawable-nodpi/wallpaper_{name}.webp')
    icons = full_assets / 'parent/site-icons.js'
    shutil.copyfile(icons, assets_dir / 'parent/site-icons.js')
    for icon, path in re.findall(r"^ *\['([a-z]+)','[^']+','#[a-f0-9]+','([^']+)'\]", icons.read_text(), re.M):
        (res_dir / f'drawable/site_option_{icon}.xml').write_text(
            '<vector xmlns:android="http://schemas.android.com/apk/res/android" '
            'android:width="28dp" android:height="28dp" android:viewportWidth="24" android:viewportHeight="24">'
            f'<path android:pathData="{path}" android:strokeColor="#ffffff" android:strokeWidth="1.8" '
            'android:strokeLineCap="round" android:strokeLineJoin="round"/></vector>')
    subprocess.run([GRADLE, '--project-dir', str(ROOT), ':assembleRelease',
                    f'-PjileResources={res_dir}', f'-PjileAssets={assets_dir}'],
                   check=True, env={**os.environ,
                                    'ANDROID_HOME': str(SDK)})
    signed = ROOT / 'build/outputs/apk/release/jile-v5-release.apk'
    subprocess.run([str(TOOLS/'apksigner'), 'verify', str(signed)], check=True,
                   env=os.environ)
    shutil.copyfile(signed, OUTPUT)
print(OUTPUT)
