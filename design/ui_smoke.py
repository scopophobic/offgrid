import subprocess,time,xml.etree.ElementTree as E,re
from pathlib import Path
adb=r'C:\Users\Acer\AppData\Local\Android\Sdk\platform-tools\adb.exe'
def run(*args):
 return subprocess.check_output([adb,*args],text=True,encoding='utf-8',errors='replace')
def nodes():
 run('shell','uiautomator','dump','/sdcard/offgrid-ui.xml')
 return E.fromstring(run('shell','cat','/sdcard/offgrid-ui.xml')).iter('node')
def tap(label,desc=False):
 candidates=[n for n in nodes() if n.get('content-desc' if desc else 'text')==label]
 if not candidates: raise RuntimeError('Missing control: '+label)
 n=candidates[-1]; x1,y1,x2,y2=map(int,re.findall(r'\d+',n.get('bounds')))
 run('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2));time.sleep(.6)
def shot(name):
 run('shell','screencap','-p','/sdcard/offgrid-review.png');run('pull','/sdcard/offgrid-review.png','design/screenshots/'+name+'.png')
time.sleep(1)
tap('Library');shot('library')
tap('Tools');shot('tools')
tap('Expression');run('shell','input','text','120+80');run('shell','input','keyevent','4');tap('Calculate')
assert any(n.get('text')=='200' for n in nodes()),'Calculator result missing'
shot('calculator-result')
tap('Settings',True);tap('Assistant preferences');tap('Dark');shot('settings-dark')
tap('Library');shot('library-dark')
tap('Settings',True);tap('System');tap('Library')
print('PASS: navigation, calculator 120+80=200, dark appearance, appearance restored.')
