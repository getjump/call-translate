import React, {CSSProperties} from 'react';
import {
  AbsoluteFill,
  Audio,
  Composition,
  interpolate,
  Sequence,
  spring,
  staticFile,
  useCurrentFrame,
} from 'remotion';
import './style.css';
import {Inheritance} from './Inheritance';

const C = {
  blue: '#214BFA',
  paper: '#FFF9ED',
  coral: '#FF765F',
  ink: '#15233F',
  ice: '#B6D9FF',
  mute: '#60708B',
};
const ease = (f: number, a: number, b: number, x: number, y: number) =>
  interpolate(f, [a, b], [x, y], {extrapolateLeft: 'clamp', extrapolateRight: 'clamp'});
const pop = (f: number, delay = 0) =>
  spring({frame: f - delay, fps: 30, config: {damping: 18, stiffness: 165, mass: 0.8}});

const Text: React.FC<{children: React.ReactNode; style?: CSSProperties}> = ({children, style}) => (
  <div style={{fontFamily: 'Inter', fontWeight: 600, whiteSpace: 'pre-line', ...style}}>
    {children}
  </div>
);
const Head: React.FC<{children: React.ReactNode; style?: CSSProperties}> = ({children, style}) => (
  <Text
    style={{
      fontFamily: 'Unbounded',
      fontWeight: 800,
      fontSize: 96,
      lineHeight: 1.14,
      letterSpacing: -5,
      ...style,
    }}
  >
    {children}
  </Text>
);

const PhoneIcon: React.FC<{size?: number; color?: string}> = ({size = 60, color = C.paper}) => (
  <svg width={size} height={size} viewBox="0 0 64 64" fill="none">
    <path
      d="M18 10L11 17C8 20 14 33 24 43C34 53 47 58 50 54L57 47L43 34L35 40C29 37 25 33 22 27L28 20L18 10Z"
      fill={color}
    />
  </svg>
);
const MicIcon = () => (
  <svg width="43" height="55" viewBox="0 0 43 55" fill="none">
    <rect x="14" y="2" width="15" height="29" rx="7.5" fill={C.ink} />
    <path
      d="M6 23C6 47 37 47 37 23M21.5 42V52M11 52H32"
      stroke={C.ink}
      strokeWidth="4"
      strokeLinecap="round"
    />
  </svg>
);
const Check = ({color = C.blue}: {color?: string}) => (
  <svg width="35" height="28" viewBox="0 0 35 28" fill="none">
    <path
      d="M3 14L13 24L32 3"
      stroke={color}
      strokeWidth="6"
      strokeLinecap="round"
      strokeLinejoin="round"
    />
  </svg>
);

const Wave: React.FC<{width?: number; height?: number; color?: string; quiet?: boolean}> = ({
  width = 680,
  height = 126,
  color = C.blue,
  quiet = false,
}) => {
  const f = useCurrentFrame();
  const count = 37;
  return (
    <div
      style={{
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'space-between',
        width,
        height,
      }}
    >
      {Array.from({length: count}, (_, i) => {
        const envelope = Math.sin((Math.PI * (i + 0.5)) / count);
        const level = quiet
          ? 0.14
          : 0.25 + 0.75 * Math.abs(Math.sin(f * 0.17 + i * 1.81) * Math.cos(f * 0.061 - i * 0.43));
        return (
          <div
            key={i}
            style={{
              height: Math.max(10, height * envelope * level),
              width: 11,
              borderRadius: 12,
              background: color,
            }}
          />
        );
      })}
    </div>
  );
};

const Mark: React.FC<{dark?: boolean; size?: number}> = ({dark = false, size = 82}) => (
  <svg width={size} height={size} viewBox="0 0 88 88" fill="none">
    <path
      d="M8 10H61C70 10 77 17 77 26V43C77 52 70 59 61 59H31L13 75V59H8V10Z"
      fill={dark ? C.paper : C.blue}
    />
    <path
      d="M34 25H46M31 35H49M36 45H44"
      stroke={dark ? C.blue : C.paper}
      strokeWidth="6"
      strokeLinecap="round"
    />
    <path d="M54 42H81V70H77V81L65 70H54V42Z" fill={C.coral} />
  </svg>
);

const Top: React.FC<{dark?: boolean; concept?: boolean}> = ({dark = false, concept = false}) => (
  <div
    style={{
      position: 'absolute',
      top: 72,
      left: 74,
      right: 74,
      display: 'flex',
      alignItems: 'center',
      justifyContent: 'space-between',
      color: dark ? C.paper : C.ink,
    }}
  >
    <div style={{display: 'flex', alignItems: 'center', gap: 17}}>
      <Mark size={55} dark={dark} />
      <Text style={{fontSize: 29, fontWeight: 800, letterSpacing: -0.7}}>Call Translate</Text>
    </div>
    <Text
      style={{
        fontSize: 25,
        padding: '12px 20px',
        borderRadius: 24,
        background: concept ? C.coral : dark ? 'rgba(255,249,237,0.14)' : 'rgba(33,75,250,0.08)',
        color: concept ? C.ink : undefined,
      }}
    >
      {concept ? 'Концепт' : 'Перевод звонка'}
    </Text>
  </div>
);

const Bottom: React.FC<{dark?: boolean; concept?: boolean}> = ({dark = false, concept = false}) => (
  <div
    style={{
      position: 'absolute',
      bottom: 62,
      left: 74,
      right: 74,
      display: 'flex',
      justifyContent: 'space-between',
      gap: 25,
      color: dark ? 'rgba(255,249,237,0.65)' : C.mute,
    }}
  >
    <Text style={{fontSize: 24}}>
      {concept ? 'Демонстрация сценария' : 'Демонстрация интерфейса'}
    </Text>
    <Text style={{fontSize: 24}}>{concept ? 'ElevenLabs' : 'Сербский -> Русский'}</Text>
  </div>
);

const Frame: React.FC<{children: React.ReactNode; blue?: boolean; concept?: boolean}> = ({
  children,
  blue = false,
  concept = false,
}) => (
  <AbsoluteFill
    style={{background: blue ? C.blue : C.paper, color: blue ? C.paper : C.ink, overflow: 'hidden'}}
  >
    <Top dark={blue} concept={concept} />
    {children}
    <Bottom dark={blue} concept={concept} />
  </AbsoluteFill>
);

const Wipe: React.FC<{at: number; color?: string}> = ({at, color = C.paper}) => {
  const f = useCurrentFrame();
  if (f < at - 9 || f > at + 9) return null;
  const x = interpolate(f, [at - 9, at, at + 9], [1120, 0, -1120]);
  return (
    <AbsoluteFill
      style={{background: color, transform: `translateX(${x}px)`, width: 1320, left: -120}}
    />
  );
};

const Incoming: React.FC = () => {
  const f = useCurrentFrame();
  return (
    <Frame blue>
      <div style={{position: 'absolute', left: 74, top: 255}}>
        <Head style={{fontSize: 149, lineHeight: 1.02, letterSpacing: -9}}>Алло?</Head>
        <Head style={{fontSize: 75, marginTop: 42, letterSpacing: -4}}>
          Тебе звонят{`\n`}по-сербски.
        </Head>
      </div>
      <div
        style={{
          position: 'absolute',
          left: 122,
          top: 835,
          width: 836,
          height: 650,
          background: C.paper,
          color: C.ink,
          borderRadius: 72,
          padding: 58,
          transform: `translateY(${(1 - pop(f)) * 32}px)`,
          boxShadow: '0 30px 0 rgba(11,31,140,0.4)',
        }}
      >
        <div style={{display: 'flex', alignItems: 'center', gap: 30}}>
          <div
            style={{
              width: 112,
              height: 112,
              background: C.ice,
              borderRadius: 50,
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
            }}
          >
            <PhoneIcon color={C.blue} size={63} />
          </div>
          <div>
            <Text style={{fontSize: 31, color: C.mute}}>Входящий звонок</Text>
            <Text style={{fontSize: 59, fontWeight: 800, marginTop: 8, letterSpacing: -2}}>
              Доставка
            </Text>
          </div>
        </div>
        <Text style={{fontSize: 47, marginTop: 54, lineHeight: 1.25}}>
          Dostava stiže{`\n`}za deset minuta...
        </Text>
        <div
          style={{
            position: 'absolute',
            bottom: 55,
            left: 58,
            right: 58,
            display: 'flex',
            justifyContent: 'space-between',
            alignItems: 'center',
          }}
        >
          <Wave width={420} height={72} color={C.blue} />
          <div
            style={{
              width: 119,
              height: 119,
              borderRadius: 60,
              background: C.coral,
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
            }}
          >
            <PhoneIcon size={61} color={C.ink} />
          </div>
        </div>
      </div>
      <Text style={{position: 'absolute', top: 1609, left: 74, fontSize: 40, color: C.ice}}>
        Знакомая ситуация?
      </Text>
    </Frame>
  );
};

const Understand: React.FC = () => {
  const f = useCurrentFrame();
  const p = pop(f, 2);
  return (
    <Frame>
      <Head style={{position: 'absolute', top: 278, left: 74, fontSize: 103}}>
        Теперь{`\n`}понятно.
      </Head>
      <div
        style={{
          position: 'absolute',
          left: 74,
          top: 743,
          width: 932,
          height: 192,
          background: C.ice,
          borderRadius: 26,
          padding: 45,
          transform: `translateX(${(1 - p) * 1050}px)`,
        }}
      >
        <Text style={{fontSize: 46, color: C.blue}}>Dostava stiže za deset minuta.</Text>
      </div>
      <div
        style={{
          position: 'absolute',
          left: 194,
          top: 987,
          width: 812,
          height: 362,
          background: C.blue,
          borderRadius: '58px 58px 12px 58px',
          padding: 52,
          transform: `scale(${0.82 + 0.18 * pop(f, 13)})`,
          opacity: ease(f, 8, 15, 0, 1),
          transformOrigin: 'right bottom',
        }}
      >
        <Text
          style={{
            fontSize: 69,
            fontWeight: 800,
            color: C.paper,
            lineHeight: 1.15,
            letterSpacing: -2,
          }}
        >
          Доставка{`\n`}приедет через{`\n`}10 минут.
        </Text>
      </div>
      <div style={{position: 'absolute', top: 1477, left: 74}}>
        <Wave width={932} height={112} />
        <Text style={{fontSize: 36, marginTop: 55, color: C.mute}}>
          Речь превращается в перевод.
        </Text>
      </div>
    </Frame>
  );
};

const TranscriptPhone: React.FC = () => {
  const f = useCurrentFrame();
  const reveal = Math.floor(ease(f, 15, 65, 8, 73));
  const original = 'Достава стиже за десет минута. Да ли сте код куће?';
  const final = f >= 119;
  const draft =
    f < 57
      ? 'Доставка приедет...'
      : f < 89
        ? 'Доставка приедет через десять минут...'
        : 'Доставка приедет через десять минут. Вы дома?';
  const translated = final ? 'Доставка приедет через 10 минут. Вы сейчас дома?' : draft;
  return (
    <div
      style={{
        position: 'absolute',
        width: 800,
        height: 1190,
        left: 140,
        top: 493,
        background: C.ink,
        borderRadius: 79,
        padding: 16,
        transform: `translateY(${(1 - pop(f)) * 150}px) scale(${0.96 + 0.04 * pop(f)})`,
        boxShadow: '0 30px 0 rgba(21,35,63,0.1)',
      }}
    >
      <div
        style={{
          height: '100%',
          background: C.paper,
          borderRadius: 66,
          overflow: 'hidden',
          padding: '62px 40px 42px',
        }}
      >
        <div
          style={{
            position: 'absolute',
            left: 290,
            top: 29,
            width: 220,
            height: 32,
            borderRadius: 22,
            background: C.ink,
          }}
        />
        <div style={{display: 'flex', alignItems: 'center', justifyContent: 'space-between'}}>
          <Text style={{fontSize: 36, fontWeight: 800}}>Доставка</Text>
          <Text style={{fontSize: 28, color: C.mute}}>
            00:{String(12 + Math.floor(f / 30)).padStart(2, '0')}
          </Text>
        </div>
        <div style={{marginTop: 36, display: 'flex', alignItems: 'center', gap: 13}}>
          <div
            style={{width: 14, height: 14, borderRadius: 8, background: final ? C.blue : C.coral}}
          />
          <Text style={{fontSize: 26, color: C.mute}}>
            {final ? 'Фраза готова' : 'Слушаю собеседника'}
          </Text>
        </div>
        <div
          style={{
            marginTop: 49,
            padding: 30,
            background: '#E9EEF7',
            borderRadius: '28px 28px 28px 8px',
            minHeight: 224,
          }}
        >
          <Text style={{fontSize: 24, color: C.mute, marginBottom: 21}}>Сербский</Text>
          <Text style={{fontSize: 38, lineHeight: 1.28}}>
            {original.slice(0, reveal)}
            {f < 65 ? '|' : ''}
          </Text>
        </div>
        <div
          style={{
            marginTop: 28,
            padding: 35,
            background: C.blue,
            color: C.paper,
            borderRadius: '35px 35px 10px 35px',
            minHeight: 355,
          }}
        >
          <div
            style={{
              display: 'flex',
              justifyContent: 'space-between',
              alignItems: 'center',
              marginBottom: 23,
            }}
          >
            <Text style={{fontSize: 24, opacity: 0.72}}>Русский</Text>
            <Text
              style={{
                fontSize: 23,
                background: 'rgba(255,249,237,0.14)',
                padding: '7px 13px',
                borderRadius: 16,
              }}
            >
              {final ? 'Перевод' : 'Черновик'}
            </Text>
          </div>
          <Text style={{fontSize: 47, fontWeight: 800, lineHeight: 1.19, letterSpacing: -1}}>
            {translated}
          </Text>
        </div>
        <div
          style={{
            position: 'absolute',
            bottom: 63,
            left: 58,
            right: 58,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
          }}
        >
          <Wave width={478} height={72} color={C.blue} quiet={final} />
          <div
            style={{
              width: 102,
              height: 102,
              borderRadius: 51,
              background: C.coral,
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
            }}
          >
            <PhoneIcon color={C.ink} size={54} />
          </div>
        </div>
      </div>
    </div>
  );
};

const LiveDemo: React.FC = () => (
  <Frame>
    <Head style={{position: 'absolute', left: 74, top: 235, fontSize: 75}}>
      Перевод{`\n`}в ходе звонка.
    </Head>
    <TranscriptPhone />
    <Text style={{position: 'absolute', left: 74, top: 1740, fontSize: 35}}>
      Черновик обновляется в том же пузыре.
    </Text>
  </Frame>
);

const Languages: React.FC = () => {
  const f = useCurrentFrame();
  const languages = ['Сербский', 'Русский', 'English', 'Deutsch'];
  return (
    <Frame>
      <Head style={{position: 'absolute', top: 265, left: 74, fontSize: 90}}>
        Выбери{`\n`}свой язык.
      </Head>
      <div
        style={{
          position: 'absolute',
          left: 74,
          top: 777,
          display: 'flex',
          gap: 22,
          flexWrap: 'wrap',
          width: 932,
        }}
      >
        {languages.map((lang, i) => (
          <Text
            key={lang}
            style={{
              fontSize: 44,
              fontWeight: 800,
              padding: '33px 41px',
              borderRadius: 55,
              background: i < 2 ? C.blue : C.ice,
              color: i < 2 ? C.paper : C.ink,
              transform: `translateY(${(1 - pop(f, i * 4)) * 80}px)`,
            }}
          >
            {lang}
            {i < 2 ? (
              <span style={{display: 'inline-flex', marginLeft: 24, verticalAlign: 'middle'}}>
                <Check color={C.paper} />
              </span>
            ) : null}
          </Text>
        ))}
      </div>
      <div
        style={{
          position: 'absolute',
          top: 1185,
          left: 74,
          width: 932,
          height: 446,
          border: '3px solid ' + C.blue,
          borderRadius: 46,
          padding: 48,
        }}
      >
        <div style={{display: 'flex', gap: 18, alignItems: 'center'}}>
          <div style={{height: 22, width: 22, borderRadius: 20, background: C.coral}} />
          <Text style={{fontSize: 44, fontWeight: 800}}>Онлайн или офлайн</Text>
        </div>
        <Text style={{fontSize: 41, lineHeight: 1.3, marginTop: 44, color: C.mute}}>
          В офлайн-режиме{`\n`}перевод появляется{`\n`}после фразы.
        </Text>
      </div>
    </Frame>
  );
};

const End: React.FC<{concept?: boolean}> = ({concept = false}) => {
  const f = useCurrentFrame();
  return (
    <Frame blue concept={concept}>
      <div style={{position: 'absolute', left: 74, top: 290}}>
        <Head style={{fontSize: concept ? 89 : 105, letterSpacing: -6}}>
          {concept ? 'Твоя задача.' : 'Звонок'}
          {`\n`}
          {concept ? 'Его голос.' : 'понятен.'}
        </Head>
      </div>
      <div
        style={{
          position: 'absolute',
          left: 74,
          top: 837,
          transform: `translateY(${(1 - pop(f, 3)) * 32}px) scale(${0.95 + 0.05 * pop(f, 3)})`,
          transformOrigin: 'left center',
        }}
      >
        <Mark size={232} dark />
      </div>
      <Head
        style={{
          position: 'absolute',
          left: 74,
          top: 1168,
          fontSize: 93,
          lineHeight: 1.2,
          letterSpacing: -5,
        }}
      >
        Call{`\n`}Translate
      </Head>
      <Text
        style={{
          position: 'absolute',
          top: 1507,
          left: 74,
          fontSize: 40,
          lineHeight: 1.34,
          color: C.ice,
        }}
      >
        {concept
          ? 'Голосовой агент для звонков.\nКонцепт на ElevenLabs.'
          : 'Перевод речи\nво время звонка.'}
      </Text>
    </Frame>
  );
};

const AgentHook: React.FC = () => {
  const f = useCurrentFrame();
  return (
    <Frame concept>
      <Head style={{position: 'absolute', left: 74, top: 270, fontSize: 86}}>
        Не знаешь,{`\n`}как сказать?
      </Head>
      <div
        style={{
          position: 'absolute',
          top: 762,
          left: 74,
          width: 932,
          background: C.blue,
          borderRadius: 64,
          padding: 57,
          transform: `translateY(${(1 - pop(f)) * 32}px)`,
        }}
      >
        <div style={{display: 'flex', alignItems: 'center', gap: 26, marginBottom: 38}}>
          <div
            style={{
              background: C.coral,
              width: 94,
              height: 94,
              borderRadius: 50,
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
            }}
          >
            <PhoneIcon color={C.ink} size={54} />
          </div>
          <div>
            <Text style={{fontSize: 26, color: C.ice}}>Исходящий звонок</Text>
            <Text style={{fontSize: 39, color: C.paper, fontWeight: 800, marginTop: 8}}>
              Служба доставки
            </Text>
          </div>
        </div>
        <Text
          style={{
            fontSize: 75,
            color: C.paper,
            fontWeight: 800,
            lineHeight: 1.16,
            letterSpacing: -3,
          }}
        >
          Где{`\n`}мой заказ?
        </Text>
      </div>
      <div
        style={{
          position: 'absolute',
          top: 1420,
          left: 74,
          display: 'flex',
          alignItems: 'center',
          gap: 29,
        }}
      >
        <div
          style={{
            background: C.coral,
            borderRadius: 63,
            width: 126,
            height: 126,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
          }}
        >
          <MicIcon />
        </div>
        <Text style={{fontSize: 41, lineHeight: 1.26}}>
          Команда агенту.{`\n`}Собеседник ее не слышит.
        </Text>
      </div>
    </Frame>
  );
};

const AgentTask: React.FC = () => {
  const f = useCurrentFrame();
  return (
    <Frame blue concept>
      <Head style={{position: 'absolute', left: 74, top: 278, fontSize: 85}}>
        Ты задаешь{`\n`}задачу.
      </Head>
      <div
        style={{
          position: 'absolute',
          left: 74,
          top: 760,
          width: 932,
          background: C.paper,
          color: C.ink,
          borderRadius: 58,
          padding: 58,
          transform: `scale(${0.94 + 0.06 * pop(f)})`,
        }}
      >
        <div style={{display: 'flex', alignItems: 'center', gap: 20, marginBottom: 40}}>
          <MicIcon />
          <Text style={{fontSize: 28, color: C.mute}}>Приватная команда владельца</Text>
        </div>
        <Text style={{fontSize: 70, fontWeight: 800, letterSpacing: -2, lineHeight: 1.19}}>
          Уточни, когда{`\n`}привезут заказ.
        </Text>
        <div style={{marginTop: 57}}>
          <Wave width={816} height={113} />
        </div>
      </div>
      <div
        style={{
          position: 'absolute',
          top: 1450,
          left: 74,
          display: 'flex',
          alignItems: 'center',
          gap: 26,
        }}
      >
        <PhoneIcon size={61} />
        <Text style={{fontSize: 36, color: C.ice, lineHeight: 1.4}}>
          В звонке: служба доставки.{`\n`}Агент отвечает по-сербски.
        </Text>
      </div>
    </Frame>
  );
};

const AgentSpeech: React.FC = () => {
  const f = useCurrentFrame();
  const p = pop(f, 4);
  const active = f < 150;
  const phrase = 'Dobar dan! Možete li da mi kažete kada stiže moja porudžbina?';
  const count = Math.floor(ease(f, 14, 136, 10, phrase.length));
  return (
    <Frame concept>
      <Head style={{position: 'absolute', left: 74, top: 257, fontSize: 78}}>
        Агент отвечает{`\n`}по телефону.
      </Head>
      <div
        style={{
          position: 'absolute',
          top: 601,
          left: 108,
          width: 864,
          height: 1060,
          border: `14px solid ${C.ink}`,
          borderRadius: 68,
          padding: '69px 37px 35px',
          transform: `translateY(${(1 - p) * 85}px)`,
        }}
      >
        <div
          style={{
            position: 'absolute',
            left: 302,
            top: 15,
            width: 232,
            height: 28,
            borderRadius: 15,
            background: C.ink,
          }}
        />
        <div style={{display: 'flex', alignItems: 'center', justifyContent: 'space-between'}}>
          <div>
            <Text style={{fontSize: 25, color: C.mute, marginBottom: 9}}>Исходящий звонок</Text>
            <Text style={{fontSize: 40, fontWeight: 800}}>Служба доставки</Text>
          </div>
          <Text style={{fontSize: 29, color: C.mute}}>
            00:{String(12 + Math.floor(f / 30)).padStart(2, '0')}
          </Text>
        </div>
        <div
          style={{
            marginTop: 39,
            height: 98,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
          }}
        >
          <Wave width={750} height={89} color={C.coral} quiet={!active} />
        </div>
        <div
          style={{
            marginTop: 37,
            width: '100%',
            minHeight: 341,
            background: C.blue,
            borderRadius: '38px 38px 38px 10px',
            padding: 34,
          }}
        >
          <Text style={{fontSize: 24, color: C.ice, marginBottom: 23}}>Агент / сербский</Text>
          <Text
            style={{
              fontSize: 49,
              lineHeight: 1.22,
              color: C.paper,
              fontWeight: 800,
              letterSpacing: -1.1,
            }}
          >
            {phrase.slice(0, count)}
            {active ? '|' : ''}
          </Text>
        </div>
        <Text style={{fontSize: 35, lineHeight: 1.27, color: C.mute, marginTop: 36}}>
          Добрый день! Подскажите,{`\n`}когда приедет мой заказ?
        </Text>
        <div
          style={{
            position: 'absolute',
            bottom: 34,
            left: 351,
            width: 132,
            height: 104,
            borderRadius: 55,
            background: C.coral,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
          }}
        >
          <PhoneIcon color={C.ink} size={61} />
        </div>
      </div>
      <Text style={{position: 'absolute', left: 74, top: 1722, fontSize: 29, color: C.mute}}>
        Озвученная модель телефонного сценария.
      </Text>
    </Frame>
  );
};

const Goal: React.FC = () => {
  const f = useCurrentFrame();
  return (
    <Frame concept>
      <Head style={{position: 'absolute', top: 293, left: 74, fontSize: 83}}>
        Ты звонишь.{`\n`}Агент говорит.
      </Head>
      <div
        style={{
          position: 'absolute',
          top: 908,
          left: 74,
          width: 932,
          display: 'flex',
          alignItems: 'center',
          gap: 35,
        }}
      >
        <div
          style={{
            width: 110,
            height: 110,
            borderRadius: 60,
            background: C.blue,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            transform: `scale(${pop(f)})`,
          }}
        >
          <Check color={C.paper} />
        </div>
        <Text style={{fontSize: 51, fontWeight: 800}}>Ты выбираешь цель.</Text>
      </div>
      <div
        style={{
          position: 'absolute',
          top: 1170,
          left: 74,
          width: 932,
          display: 'flex',
          alignItems: 'center',
          gap: 35,
        }}
      >
        <div
          style={{
            width: 110,
            height: 110,
            borderRadius: 60,
            background: C.coral,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            transform: `scale(${pop(f, 7)})`,
          }}
        >
          <Check color={C.ink} />
        </div>
        <Text style={{fontSize: 51, fontWeight: 800}}>Агент подбирает слова.</Text>
      </div>
      <Text
        style={{
          position: 'absolute',
          left: 74,
          top: 1576,
          fontSize: 38,
          lineHeight: 1.34,
          color: C.mute,
        }}
      >
        Концепт телефонного режима.{`\n`}Агент еще в разработке.
      </Text>
    </Frame>
  );
};

const Translation: React.FC = () => (
  <AbsoluteFill>
    <Sequence durationInFrames={100}>
      <Incoming />
    </Sequence>
    <Sequence from={100} durationInFrames={85}>
      <Understand />
    </Sequence>
    <Sequence from={185} durationInFrames={180}>
      <LiveDemo />
    </Sequence>
    <Sequence from={365} durationInFrames={80}>
      <Languages />
    </Sequence>
    <Sequence from={445} durationInFrames={95}>
      <End />
    </Sequence>
    <Audio src={staticFile('translation-track.wav')} />
    {[100, 185, 365, 445].map((at) => (
      <Wipe key={at} at={at} />
    ))}
  </AbsoluteFill>
);
const Agent: React.FC = () => (
  <AbsoluteFill>
    <Sequence durationInFrames={90}>
      <AgentHook />
    </Sequence>
    <Sequence from={90} durationInFrames={100}>
      <AgentTask />
    </Sequence>
    <Sequence from={190} durationInFrames={170}>
      <AgentSpeech />
    </Sequence>
    <Sequence from={360} durationInFrames={80}>
      <Goal />
    </Sequence>
    <Sequence from={440} durationInFrames={100}>
      <End concept />
    </Sequence>
    <Audio src={staticFile('agent-track.wav')} />
    {[90, 190, 360, 440].map((at) => (
      <Wipe key={at} at={at} color={C.paper} />
    ))}
  </AbsoluteFill>
);

export const Root: React.FC = () => {
  const config = {width: 1080, height: 1920, fps: 30, durationInFrames: 540};
  return (
    <>
      <Composition id="TranslationPromo" component={Translation} {...config} />
      <Composition id="VoiceAgentConcept" component={Agent} {...config} />
      <Composition
        id="IncomingInheritance"
        component={Inheritance}
        width={1080}
        height={1920}
        fps={30}
        durationInFrames={600}
      />
    </>
  );
};
