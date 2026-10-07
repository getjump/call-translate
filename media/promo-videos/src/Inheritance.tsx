import React from 'react';
import {AbsoluteFill, Audio, interpolate, spring, staticFile, useCurrentFrame} from 'remotion';

const theme = {
  paper: '#EDF1F4',
  ink: '#16263B',
  blue: '#2C5580',
  muted: '#6A7D8E',
  wine: '#7D3D50',
  red: '#D84B4B',
  white: '#FFFFFF',
};
const ramp = (f: number, a: number, b: number, x: number, y: number) =>
  interpolate(f, [a, b], [x, y], {extrapolateLeft: 'clamp', extrapolateRight: 'clamp'});
const timing = {
  answer: 29,
  panic: 132,
  hangup: 165,
  ended: 180,
  pivot: 210,
  alternate: 240,
  payoff: 510,
};
const phrases = [
  {
    at: 246,
    source: 'Добар дан. Зовем из адвокатске канцеларије.',
    translation: 'Добрый день. Звоню из адвокатской конторы.',
  },
  {
    at: 339,
    source: 'Тражимо наследника покојног Милана Петровића.',
    translation: 'Мы ищем наследника покойного Милана Петровича.',
  },
  {
    at: 432,
    source: 'Оставио је винарију и милијарду динара.',
    translation: 'Он оставил винодельню и миллиард динаров.',
  },
];

const PhoneSymbol: React.FC<{color?: string; size?: number}> = ({
  color = theme.white,
  size = 60,
}) => (
  <svg width={size} height={size} viewBox="0 0 64 64">
    <path
      d="M18 10L11 17C8 20 14 33 24 43C34 53 47 58 50 54L57 47L43 34L35 40C29 37 25 33 22 27L28 20L18 10Z"
      fill={color}
    />
  </svg>
);

const Wave: React.FC<{active: boolean; wide?: boolean}> = ({active, wide = false}) => {
  const f = useCurrentFrame();
  return (
    <div
      style={{
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'space-between',
        height: wide ? 128 : 58,
        width: wide ? 600 : 428,
      }}
    >
      {Array.from({length: 35}, (_, i) => (
        <div
          key={i}
          style={{
            width: wide ? 8 : 5,
            borderRadius: 7,
            height: active
              ? 10 +
                Math.abs(Math.sin(i * 1.8 + f * 0.13)) *
                  Math.sin((Math.PI * (i + 1)) / 36) *
                  (wide ? 105 : 44)
              : 6,
            background: theme.blue,
          }}
        />
      ))}
    </div>
  );
};

const Before: React.FC<{frame: number}> = ({frame: f}) => {
  const connected = f >= timing.answer;
  const ended = f >= timing.ended;
  const panic = f >= timing.panic;
  const buttonScale =
    f >= timing.hangup && f < timing.ended ? ramp(f, timing.hangup, timing.hangup + 6, 1, 0.92) : 1;
  return (
    <>
      <div
        style={{
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
          fontSize: 26,
          color: theme.muted,
        }}
      >
        <div>
          {ended ? 'Звонок завершен' : connected ? 'Телефонный разговор' : 'Входящий звонок'}
        </div>
        <div>
          {connected
            ? `00:${String(Math.min(5, Math.floor((f - timing.answer) / 30))).padStart(2, '0')}`
            : ''}
        </div>
      </div>
      <div style={{fontSize: 42, fontWeight: 800, marginTop: 17, letterSpacing: -1.2}}>
        Неизвестный номер
      </div>
      <div style={{marginTop: 115, display: 'flex', justifyContent: 'center'}}>
        <div
          style={{
            height: 210,
            width: 210,
            background: theme.paper,
            borderRadius: 108,
            display: 'flex',
            justifyContent: 'center',
            alignItems: 'center',
          }}
        >
          <PhoneSymbol size={95} color={ended ? theme.muted : theme.blue} />
        </div>
      </div>
      <div style={{marginTop: 82, display: 'flex', justifyContent: 'center'}}>
        <Wave active={connected && !ended && f < timing.hangup} wide />
      </div>
      <div
        style={{
          fontSize: ended ? 44 : 35,
          textAlign: 'center',
          color: ended ? theme.ink : theme.muted,
          fontWeight: ended ? 800 : 600,
          marginTop: 45,
          lineHeight: 1.3,
        }}
      >
        {ended ? 'Звонок завершен' : connected ? 'Собеседник говорит...' : 'Кто звонит?'}
      </div>
      <div
        style={{
          position: 'absolute',
          left: 0,
          right: 0,
          bottom: 105,
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          gap: 65,
        }}
      >
        {!connected ? (
          <div
            style={{
              width: 118,
              height: 118,
              borderRadius: 60,
              background: theme.blue,
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
            }}
          >
            <PhoneSymbol size={55} />
          </div>
        ) : null}
        <div
          style={{
            height: connected ? 111 : 118,
            width: connected ? 286 : 118,
            borderRadius: 60,
            background: ended ? theme.paper : theme.red,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            gap: 21,
            transform: `scale(${buttonScale})`,
            boxShadow: panic && !ended ? '0 0 0 12px rgba(216,75,75,0.08)' : undefined,
          }}
        >
          <PhoneSymbol size={ended ? 46 : 51} color={ended ? theme.muted : theme.white} />
          {connected && !ended ? (
            <span style={{fontSize: 29, fontWeight: 800, color: theme.white}}>Завершить</span>
          ) : null}
        </div>
      </div>
    </>
  );
};

const Alternative: React.FC<{frame: number}> = ({frame: f}) => {
  const phrase = phrases.reduce((current, item) => (f >= item.at ? item : current), phrases[0]);
  const elapsed = Math.max(0, f - phrase.at);
  const sourceCount = Math.floor(ramp(elapsed, 0, 65, 4, phrase.source.length));
  const translatedCount = Math.floor(ramp(elapsed, 9, 76, 3, phrase.translation.length));
  const done = f >= timing.payoff;
  return (
    <>
      <div
        style={{
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
          fontSize: 25,
          color: theme.muted,
        }}
      >
        <div>Call Translate / входящий</div>
        <div>{`00:${String(Math.floor((f - timing.alternate) / 30)).padStart(2, '0')}`}</div>
      </div>
      <div style={{fontSize: 42, fontWeight: 800, marginTop: 17, letterSpacing: -1.2}}>
        Неизвестный номер
      </div>
      <div style={{fontSize: 24, color: theme.muted, marginTop: 40}}>{'Сербский -> Русский'}</div>
      <div
        style={{
          marginTop: 32,
          padding: 29,
          minHeight: 258,
          background: theme.paper,
          borderRadius: '28px 28px 28px 7px',
        }}
      >
        <div style={{fontSize: 23, color: theme.muted, marginBottom: 23}}>
          Собеседник / сербский
        </div>
        <div style={{fontSize: 37, lineHeight: 1.3}}>
          {phrase.source.slice(0, sourceCount)}
          {elapsed < 65 ? '|' : ''}
        </div>
      </div>
      <div
        style={{
          marginTop: 28,
          minHeight: 342,
          padding: 33,
          borderRadius: '34px 34px 10px 34px',
          background: done ? theme.wine : theme.blue,
          color: theme.white,
        }}
      >
        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            marginBottom: 27,
          }}
        >
          <div style={{fontSize: 23, opacity: 0.7}}>Русский</div>
          <div style={{fontSize: 22, opacity: 0.7}}>{done ? 'Фраза готова' : 'Черновик'}</div>
        </div>
        <div style={{fontSize: 46, fontWeight: 800, lineHeight: 1.2, letterSpacing: -1.2}}>
          {phrase.translation.slice(0, translatedCount)}
        </div>
      </div>
      <div
        style={{
          position: 'absolute',
          left: 40,
          right: 40,
          bottom: 83,
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
        }}
      >
        <Wave active={!done} />
        <div
          style={{
            height: 102,
            width: 102,
            borderRadius: 55,
            background: theme.red,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
          }}
        >
          <PhoneSymbol size={53} />
        </div>
      </div>
    </>
  );
};

export const Inheritance: React.FC = () => {
  const f = useCurrentFrame();
  const pivot = f >= timing.pivot && f < timing.alternate;
  const alternative = f >= timing.alternate;
  const ending = f >= timing.payoff;
  const ended = f >= timing.ended;
  const panic = f >= timing.panic;
  const entry = spring({frame: f, fps: 30, config: {damping: 25, stiffness: 120, mass: 0.85}});
  const alternateEntry = spring({
    frame: f - timing.alternate,
    fps: 30,
    config: {damping: 25, stiffness: 120, mass: 0.85},
  });
  const finish = ramp(f, timing.payoff, timing.payoff + 25, 0, 1);
  const scale = (alternative ? 0.98 + 0.02 * alternateEntry : 0.98 + 0.02 * entry) - finish * 0.16;
  const phoneY = 390 + finish * 115;
  const headline = alternative
    ? ending
      ? 'Кажется,\nзря сбросил.'
      : 'Тот же звонок.\nС переводом.'
    : ended
      ? 'Ничего не понял.\nИ сбросил.'
      : panic
        ? 'Не понимаю.\nСбрасываю.'
        : 'Звонят по-сербски.\nЧто он хочет?';
  return (
    <AbsoluteFill
      style={{
        background: theme.paper,
        color: theme.ink,
        fontFamily: 'Inter',
        fontWeight: 600,
        overflow: 'hidden',
      }}
    >
      {pivot ? (
        <AbsoluteFill style={{justifyContent: 'center', alignItems: 'center', padding: 90}}>
          <div
            style={{
              fontSize: 93,
              lineHeight: 1.15,
              fontWeight: 800,
              letterSpacing: -3,
              whiteSpace: 'pre-line',
              textAlign: 'center',
            }}
          >
            А ты мог{`\n`}бы так.
          </div>
          <div style={{fontSize: 29, color: theme.muted, marginTop: 50}}>
            Тот же звонок. Другой сценарий.
          </div>
        </AbsoluteFill>
      ) : (
        <>
          <div
            style={{
              position: 'absolute',
              top: 68,
              left: 90,
              right: 90,
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'space-between',
              fontSize: 27,
            }}
          >
            <div style={{fontWeight: 800}}>
              {alternative ? 'Call Translate' : 'Неизвестный номер'}
            </div>
            <div style={{color: theme.muted}}>
              {alternative ? 'Перевод в реальном времени' : 'Входящий звонок'}
            </div>
          </div>
          <div
            style={{
              position: 'absolute',
              left: 90,
              top: 173,
              width: 900,
              fontSize: ending ? 78 : 64,
              fontWeight: 600,
              letterSpacing: -2.9,
              lineHeight: 1.12,
              whiteSpace: 'pre-line',
            }}
          >
            {headline}
          </div>
          <div
            style={{
              position: 'absolute',
              left: 156,
              top: phoneY,
              width: 768,
              height: 1190,
              borderRadius: 78,
              padding: 14,
              background: theme.ink,
              transform: `translateY(${(1 - (alternative ? alternateEntry : entry)) * 23}px) scale(${scale})`,
              transformOrigin: 'center center',
              boxShadow: '0 29px 65px rgba(24,46,67,0.13)',
            }}
          >
            <div
              style={{
                position: 'relative',
                height: '100%',
                background: theme.white,
                borderRadius: 65,
                overflow: 'hidden',
                padding: '85px 40px 40px',
              }}
            >
              <div
                style={{
                  position: 'absolute',
                  top: 22,
                  left: 252,
                  width: 236,
                  height: 31,
                  borderRadius: 20,
                  background: theme.ink,
                }}
              />
              {alternative ? <Alternative frame={f} /> : <Before frame={f} />}
            </div>
          </div>
          <div
            style={{
              position: 'absolute',
              left: 90,
              top: 1658,
              fontSize: ending ? 37 : 34,
              color: theme.muted,
              lineHeight: 1.32,
              whiteSpace: 'pre-line',
            }}
          >
            {alternative
              ? ending
                ? 'Пойми звонок,\nпрежде чем сбросить.'
                : 'Слушай собеседника.\nСмотри перевод в ходе звонка.'
              : ended
                ? 'Бывает. Не понял и положил трубку.'
                : '...'}
          </div>
          <div
            style={{position: 'absolute', left: 90, bottom: 54, fontSize: 23, color: theme.muted}}
          >
            {alternative ? 'Альтернативный сценарий. Вымышленный звонок.' : 'Вымышленная сценка.'}
          </div>
        </>
      )}
      <Audio src={staticFile('inheritance-track.wav')} />
    </AbsoluteFill>
  );
};
