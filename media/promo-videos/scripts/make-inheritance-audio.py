import json
import subprocess
import wave
from pathlib import Path

import numpy as np

ROOT=Path(__file__).resolve().parents[1]
RATE=48000
DURATION=20
N=RATE*DURATION
track=np.zeros((N,2),dtype=np.float64)

def place(signal,at,gain=1):
    start=int(at*RATE)
    end=min(start+len(signal),N)
    track[start:end]+=signal[:end-start,None]*gain

for at,chord in [(0,[50,57]),(8,[50,57,62]),(14,[45,52,57])]:
    length=min(7,DURATION-at)
    t=np.arange(int(length*RATE))/RATE
    envelope=np.minimum(t/1.2,1)*np.minimum((length-t)/1.5,1)
    for midi in chord:
        freq=440*2**((midi-69)/12)
        sound=(np.sin(2*np.pi*freq*t)+0.17*np.sin(2*np.pi*freq*2*t))*envelope
        place(sound,at,0.017)
for at in (0.14,0.47):
    t=np.arange(int(0.18*RATE))/RATE
    ring=(np.sin(2*np.pi*660*t)+np.sin(2*np.pi*880*t))*0.5*np.sin(np.pi*t/0.18)
    place(ring,at,0.12)
voice_path=ROOT/'public/voice/inheritance-serbian.mp3'
voice_bytes=subprocess.check_output(['ffmpeg','-v','error','-i',str(voice_path),'-af','highpass=f=220,lowpass=f=3700,acompressor=threshold=0.12:ratio=2','-f','f32le','-ar',str(RATE),'-ac','1','-'])
voice=np.frombuffer(voice_bytes,dtype='<f4').astype(np.float64)
voice*=0.70/np.max(np.abs(voice))
first=voice[:int(4.55*RATE)].copy()
first[-int(0.018*RATE):]*=np.linspace(1,0,int(0.018*RATE))
place(first,0.95)
place(voice,8.0)
for at in (5.54,5.75):
    t=np.arange(int(0.105*RATE))/RATE
    hangup=np.sin(2*np.pi*480*t)*np.sin(np.pi*t/0.105)
    place(hangup,at,0.13)
t=np.arange(int(0.27*RATE))/RATE
rewind=np.sin(2*np.pi*(1300*t-1600*t*t))*np.sin(np.pi*t/0.27)
place(rewind,7.05,0.06)
fade=np.minimum(np.arange(N)/RATE/0.04,1)*np.clip((DURATION-np.arange(N)/RATE)/0.85,0,1)
track*=fade[:,None]
raw=ROOT/'public/inheritance-premaster.wav'
with wave.open(str(raw),'wb') as file:
    file.setnchannels(2)
    file.setsampwidth(2)
    file.setframerate(RATE)
    file.writeframes((np.clip(track,-1,1)*32767).astype('<i2').tobytes())
target=ROOT/'public/inheritance-track.wav'
subprocess.run(['ffmpeg','-v','error','-y','-i',str(raw),'-af','loudnorm=I=-16:TP=-1.5:LRA=9','-ar',str(RATE),'-ac','2',str(target)],check=True)
raw.unlink()
manifest={'duration':DURATION,'music':'Original slow procedural synth pad','voice':'Existing ElevenLabs eleven_v3 Serbian fictional caller reused; no new API request','first_pass':{'voice_at_s':0.95,'voice_duration_s':4.55,'hangup_at_s':5.5,'translation':False},'pivot':{'at_s':7.0,'copy':'А ты мог бы так','interpretation':'Counterfactual of the same incoming call, not post-call translation recovery'},'alternate_pass':{'voice_at_s':8.0,'voice_duration_s':round(len(voice)/RATE,3),'translation':'During the call'},'voice_filter':'Telephone bandpass 220-3700Hz'}
(ROOT/'inheritance-audio-manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')
print(f'made {target.name}',flush=True)
