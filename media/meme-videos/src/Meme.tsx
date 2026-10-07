import React from "react";
import {
  AbsoluteFill,
  Audio,
  interpolate,
  spring,
  staticFile,
  useCurrentFrame,
} from "remotion";
import "./style.css";
import { COLORS, COPY, TIMING } from "./content";

const C = COLORS;
const clamp = (frame: number, from: number, to: number, a: number, b: number) =>
  interpolate(frame, [from, to], [a, b], {
    extrapolateLeft: "clamp",
    extrapolateRight: "clamp",
  });
const pop = (frame: number, delay = 0) =>
  spring({
    frame: frame - delay,
    fps: 30,
    config: { damping: 18, stiffness: 130 },
  });
const words = (text: string) =>
  text.split("\n").map((line, i) => (
    <span key={i} style={{ display: "block" }}>
      {line}
    </span>
  ));

const Title: React.FC<{
  text: string;
  small?: boolean;
  color?: string;
  top?: number;
}> = ({ text, small, color = C.ink, top = 145 }) => (
  <div className="title" style={{ top, fontSize: small ? 62 : 79, color }}>
    {words(text)}
  </div>
);
const Label: React.FC<{
  text: string;
  top?: number;
  color?: string;
  rotate?: number;
}> = ({ text, top = 1460, color = C.yellow, rotate = 0 }) => (
  <div
    className="label"
    style={{ top, background: color, transform: `rotate(${rotate}deg)` }}
  >
    {words(text)}
  </div>
);
const Subtitle: React.FC<{ text: string; top?: number }> = ({
  text,
  top = 1620,
}) => (
  <div className="subtitle" style={{ top }}>
    {words(text)}
  </div>
);
const SVG: React.FC<{ children: React.ReactNode }> = ({ children }) => (
  <svg
    width="1080"
    height="1920"
    viewBox="0 0 1080 1920"
    style={{ position: "absolute", inset: 0 }}
  >
    {children}
  </svg>
);

const Dude: React.FC<{
  x?: number;
  y?: number;
  scale?: number;
  mood?: "study" | "panic" | "smug" | "shock" | "sad" | "happy";
  frame: number;
  phone?: boolean;
  waiter?: boolean;
  hand?: boolean;
  talk?: boolean;
  rotate?: number;
}> = ({
  x = 340,
  y = 890,
  scale = 1,
  mood = "study",
  frame,
  phone,
  waiter,
  hand,
  talk,
  rotate = 0,
}) => {
  const bob = 0;
  const blink = frame % 91 < 4;
  const color = waiter ? C.purple : C.blue;
  const eyeY = mood === "shock" ? -64 : -60;
  return (
    <g
      transform={`translate(${x} ${y + bob}) scale(${scale}) rotate(${rotate})`}
      stroke={C.ink}
      strokeWidth={11}
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      <ellipse
        cx="0"
        cy="578"
        rx="205"
        ry="34"
        fill={C.ink}
        stroke="none"
        opacity=".12"
      />
      <path
        d="M -92 390 L -113 550 L -52 550 L -16 400 M 92 390 L 115 550 L 56 550 L 16 400"
        fill={C.ink}
      />
      <path
        d="M -121 535 Q -158 540 -159 580 L -47 580 L -47 538 M 54 538 L 54 580 L 166 580 Q 168 546 115 539"
        fill={C.paper}
      />
      <path d="M -55 78 L -55 149 L 55 149 L 55 78" fill={C.skin} />
      <path
        d="M -100 119 Q -157 121 -169 209 L -160 405 Q 0 449 160 405 L 169 209 Q 157 121 100 119 L 45 150 L 0 226 L -45 150 Z"
        fill={color}
      />
      <path
        d="M -46 154 L 0 226 L 46 154 L 28 319 L -27 319 Z"
        fill={C.paper}
      />
      <path d="M 0 226 L 0 416" fill="none" />
      {waiter && (
        <path
          d="M -110 251 L 110 251 L 126 415 Q 0 442 -126 415 Z"
          fill={C.paper}
        />
      )}
      <path
        d="M -144 172 Q -221 263 -187 359 Q -170 380 -135 357"
        fill="none"
        stroke={color}
        strokeWidth={53}
      />
      <path
        d={
          phone || hand
            ? "M 146 172 Q 219 203 203 84 Q 196 17 135 -4"
            : "M 146 172 Q 211 257 187 359 Q 173 380 133 357"
        }
        fill="none"
        stroke={C.ink}
        strokeWidth={64}
      />
      <path
        d={
          phone || hand
            ? "M 146 172 Q 219 203 203 84 Q 196 17 135 -4"
            : "M 146 172 Q 211 257 187 359 Q 173 380 133 357"
        }
        fill="none"
        stroke={color}
        strokeWidth={46}
      />
      <ellipse cx="-150" cy="357" rx="30" ry="26" fill={C.skin} />
      <ellipse
        cx={phone || hand ? 136 : 150}
        cy={phone || hand ? -8 : 357}
        rx="31"
        ry="27"
        fill={C.skin}
      />
      <circle cx="-121" cy="-36" r="26" fill={C.skin} />
      <circle cx="121" cy="-36" r="26" fill={C.skin} />
      <path
        d="M -113 -94 Q -116 -170 0 -170 Q 116 -170 113 -94 L 110 19 Q 100 107 0 109 Q -100 107 -110 19 Z"
        fill={C.skin}
      />
      <path
        d={
          waiter
            ? "M -111 -71 Q -149 -137 -97 -156 Q -122 -181 -40 -193 Q 18 -205 68 -180 Q 149 -176 122 -89 L 92 -113 L 72 -153 Q -9 -108 -72 -135 L -85 -79 Z"
            : "M -111 -74 Q -147 -125 -118 -161 Q -87 -186 -44 -171 Q 4 -230 67 -184 Q 140 -164 127 -94 L 98 -110 L 88 -149 Q 30 -107 -61 -121 L -83 -74 Z"
        }
        fill={waiter ? "#B7B7C6" : C.ink}
      />
      {!waiter && (
        <path
          d="M -106 12 Q -85 66 -53 35 Q 0 6 53 35 Q 85 66 106 12 L 91 70 Q 0 149 -91 70 Z"
          fill={C.ink}
        />
      )}
      {waiter && (
        <path
          d="M 0 -1 Q -19 -24 -48 -7 Q -73 5 -78 -11 Q -68 34 -20 18 L 0 7 L 20 18 Q 68 34 78 -11 Q 73 5 48 -7 Q 19 -24 0 -1"
          fill={C.ink}
        />
      )}
      <path d="M 2 -54 L 22 -4 L -6 -1" fill="none" strokeWidth={7} />
      <path
        d={
          mood === "sad"
            ? "M -77 -98 L -25 -108 M 29 -108 L 81 -98"
            : mood === "panic" || mood === "shock"
              ? "M -80 -119 L -27 -110 M 29 -110 L 80 -119"
              : mood === "smug"
                ? "M -81 -95 L -29 -98 M 27 -110 L 79 -100"
                : "M -80 -100 L -27 -109 M 29 -109 L 80 -100"
        }
        fill="none"
      />
      {blink && mood !== "shock" ? (
        <path d="M -75 -60 L -28 -60 M 30 -60 L 76 -60" fill="none" />
      ) : (
        <>
          <ellipse
            cx="-51"
            cy={eyeY}
            rx={mood === "shock" ? 33 : 26}
            ry={mood === "sad" ? 16 : mood === "shock" ? 39 : 29}
            fill={C.paper}
          />
          <ellipse
            cx="53"
            cy={eyeY}
            rx={mood === "shock" ? 33 : 26}
            ry={mood === "sad" ? 16 : mood === "shock" ? 39 : 29}
            fill={C.paper}
          />
          <ellipse
            cx={mood === "sad" ? -37 : -45}
            cy={eyeY + (mood === "sad" ? 7 : 2)}
            rx={mood === "shock" ? 6 : 8}
            ry={mood === "shock" ? 7 : 11}
            fill={C.ink}
            stroke="none"
          />
          <ellipse
            cx={mood === "sad" ? 66 : 59}
            cy={eyeY + (mood === "sad" ? 7 : 2)}
            rx={mood === "shock" ? 6 : 8}
            ry={mood === "shock" ? 7 : 11}
            fill={C.ink}
            stroke="none"
          />
        </>
      )}
      {talk && frame % 9 > 2 ? (
        <ellipse
          cx="0"
          cy="46"
          rx="25"
          ry={10 + Math.abs(Math.sin(frame * 0.67)) * 13}
          fill={C.pink}
          stroke={waiter ? C.ink : C.paper}
          strokeWidth={5}
        />
      ) : mood === "shock" || mood === "panic" ? (
        <ellipse
          cx="0"
          cy="48"
          rx={mood === "shock" ? 23 : 16}
          ry={mood === "shock" ? 28 : 14}
          fill={C.pink}
          stroke={C.paper}
          strokeWidth={5}
        />
      ) : (
        <path
          d={
            mood === "sad"
              ? "M -29 56 Q 0 32 29 56"
              : mood === "smug"
                ? "M -31 44 Q 17 67 49 25"
                : "M -33 39 Q 0 67 33 39"
          }
          fill="none"
          stroke={waiter ? C.ink : C.paper}
          strokeWidth={7}
        />
      )}
      {mood === "panic" && (
        <>
          <path
            d="M 149 -90 Q 130 -52 151 -46 Q 174 -52 149 -90 Z"
            fill={C.mint}
            strokeWidth={5}
          />
          <path
            d="M -153 -2 Q -174 30 -158 40 Q -134 38 -153 -2 Z"
            fill={C.mint}
            strokeWidth={5}
          />
        </>
      )}
      {phone && (
        <g transform="translate(159 -19) rotate(-12)">
          <rect x="-27" y="-94" width="84" height="154" rx="18" fill={C.ink} />
          <rect
            x="-15"
            y="-77"
            width="60"
            height="111"
            rx="9"
            fill={C.mint}
            strokeWidth={5}
          />
          <path
            d="M -2 -17 Q 4 -41 12 -15 Q 20 9 28 -19"
            fill="none"
            strokeWidth={5}
          />
        </g>
      )}
    </g>
  );
};

const Drink: React.FC<{
  kind: "water" | "beer";
  x?: number;
  y?: number;
  scale?: number;
  frame?: number;
}> = ({ kind, x = 720, y = 1350, scale = 1, frame = 0 }) => (
  <g
    transform={`translate(${x} ${y}) scale(${scale})`}
    stroke={C.ink}
    strokeWidth="10"
    strokeLinejoin="round"
  >
    <ellipse
      cx="0"
      cy="34"
      rx={kind === "beer" ? 132 : 101}
      ry="28"
      fill={C.ink}
      stroke="none"
      opacity=".17"
    />
    {kind === "beer" ? (
      <>
        <path
          d="M 86 -218 Q 187 -229 176 -129 Q 171 -49 96 -52"
          fill="none"
          strokeWidth="35"
        />
        <path
          d="M 85 -218 Q 177 -226 169 -129 Q 165 -59 96 -61"
          fill="none"
          stroke={C.yellow}
          strokeWidth="17"
        />
        <path
          d="M -99 -257 L 99 -257 L 91 13 Q 0 35 -91 13 Z"
          fill={C.orange}
        />
        <path
          d="M -67 -178 L -64 -25 M -11 -178 L -11 -18 M 45 -178 L 43 -25"
          stroke="#FFCA63"
          strokeWidth="13"
        />
        <path
          d="M -102 -239 Q -129 -267 -102 -292 Q -84 -325 -46 -305 Q -16 -351 27 -310 Q 65 -335 87 -300 Q 128 -305 120 -263 L 90 -245 L 43 -255 L 19 -222 L -13 -255 L -44 -228 L -76 -251 Z"
          fill={C.paper}
        />
        {Array.from({ length: 6 }, (_, i) => (
          <circle
            key={i}
            cx={-70 + i * 26}
            cy={-35 - ((frame * 2 + i * 42) % 150)}
            r={6 + (i % 3)}
            fill={C.yellow}
            stroke="none"
          />
        ))}
      </>
    ) : (
      <>
        <path d="M -76 -237 L 76 -237 L 59 14 Q 0 33 -59 14 Z" fill="#C2EBF0" />
        <path
          d="M -68 -160 Q 0 -139 68 -160 L 59 14 Q 0 33 -59 14 Z"
          fill={C.mint}
          strokeWidth="6"
        />
        <path d="M -46 -211 L -36 -32" stroke={C.paper} strokeWidth="15" />
        <ellipse cx="0" cy="-236" rx="76" ry="14" fill={C.paper} />
        <path
          d="M -22 -139 L 22 -148 L 34 -104 L -8 -97 Z"
          fill={C.paper}
          opacity=".7"
          strokeWidth="5"
        />
      </>
    )}
  </g>
);

const CallerPortrait: React.FC<{
  frame: number;
  mood: "panic" | "smug" | "shock" | "sad" | "happy";
  talking?: boolean;
}> = ({ frame, mood, talking }) => (
  <div className="caller-portrait">
    <svg width="134" height="134" viewBox="-180 -210 360 360">
      <Dude x={0} y={0} frame={frame} mood={mood} phone talk={talking} />
    </svg>
  </div>
);
const DrinkIcon: React.FC<{ kind: "water" | "beer"; frame?: number }> = ({
  kind,
  frame = 0,
}) => (
  <svg width="143" height="180" viewBox="-180 -352 360 423">
    <Drink kind={kind} x={0} y={0} frame={frame} />
  </svg>
);
const CallScreen: React.FC<{
  frame: number;
  drink?: "water" | "beer";
  shock?: boolean;
  sad?: boolean;
  speaking?: boolean;
  celebrate?: boolean;
}> = ({ frame, drink, shock, sad, speaking, celebrate }) => {
  const order = shock || sad || drink;
  const confirmed = shock || sad || celebrate;
  const beer = drink === "beer" || shock;
  return (
    <>
      <SVG>
        <rect width="1080" height="1920" fill={C.paper} />
      </SVG>
      <div
        className="call-product-phone"
        style={{
          transform: `translateY(${clamp(frame, 0, 12, 34, 0)}px) scale(${confirmed ? clamp(frame, 6, 23, 1, 1.07) : 1})`,
        }}
      >
        <div className="call-topline">
          <span>Голосовой звонок</span>
          <span className="call-dot" />
          На линии
        </div>
        <div className="call-recipient">
          <svg width="90" height="90" viewBox="0 0 100 100">
            <rect width="100" height="100" rx="31" fill={C.ink} />
            <path
              d="M 15 72 L 87 72 M 24 63 Q 28 27 50 27 Q 72 27 78 63 M 47 21 L 53 21"
              fill="none"
              stroke={C.paper}
              strokeWidth="8"
              strokeLinecap="round"
            />
          </svg>
          <strong>Доставка напитков</strong>
          <span>00:{String(6 + Math.floor(frame / 30)).padStart(2, "0")}</span>
        </div>
        <div className="call-quote">
          <div className="quote-heading">
            <CallerPortrait
              frame={frame}
              mood={
                shock
                  ? "shock"
                  : sad
                    ? "sad"
                    : speaking
                      ? "smug"
                      : celebrate
                        ? "happy"
                        : "panic"
              }
              talking={speaking}
            />
            <div>
              <span>
                {order || speaking ? "Вы говорите" : "Вы вспоминаете фразу"}
              </span>
              <strong>
                {order
                  ? beer
                    ? "Pivo, molim."
                    : "Vodu, molim."
                  : speaking
                    ? "Фраза выучена."
                    : "Мне воды. Воды."}
              </strong>
            </div>
          </div>
          <div className="small-call-wave">
            {Array.from({ length: 22 }, (_, i) => (
              <span
                key={i}
                style={{
                  height: speaking
                    ? 8 + Math.abs(Math.sin((frame + i * 7) * 0.27)) * 31
                    : 9,
                }}
              />
            ))}
          </div>
        </div>
        {order && confirmed ? (
          <div className="remote-order" style={{ opacity: pop(frame, 3) }}>
            <div>
              <span>Ответ в звонке</span>
              <strong>
                Заказ принят:
                <br />
                {beer ? "пиво" : "вода"}.
              </strong>
              <p>
                {beer
                  ? "Pivo, molim. = Пиво, пожалуйста."
                  : "Vodu, molim. = Воды, пожалуйста."}
              </p>
            </div>
            <DrinkIcon kind={beer ? "beer" : "water"} />
          </div>
        ) : (
          <div className="remote-waiting">Собеседник ждет ваш заказ...</div>
        )}
        <div className="minimal-call-controls">
          <span>Микрофон</span>
          <div className="hangup">
            <svg width="69" height="58" viewBox="0 0 80 60">
              <path
                d="M 12 37 Q 39 6 69 37 L 60 49 L 47 39 L 48 29 L 31 29 L 33 40 L 20 49 Z"
                fill={C.paper}
              />
            </svg>
          </div>
          <span>Динамик</span>
        </div>
      </div>
    </>
  );
};

const Study: React.FC<{ frame: number; variant: string; mode?: number }> = ({
  frame,
  variant,
  mode = 0,
}) => {
  const perfect = variant === "b";
  const bg = mode === 1 ? "#EDF3F8" : C.paper;
  return (
    <>
      <SVG>
        <rect width="1080" height="1920" fill={bg} />

        {mode === 0 && (
          <>
            <rect
              x="685"
              y="575"
              width="230"
              height="350"
              rx="15"
              fill={C.paper}
              stroke={C.ink}
              strokeWidth="12"
            />
            <path
              d="M 800 580 L 800 914 M 690 749 L 910 749"
              stroke={C.ink}
              strokeWidth="10"
            />
            <circle cx="835" cy="634" r="35" fill={C.coral} />
            <path
              d="M 690 811 L 738 790 L 755 821 L 790 776 L 814 835 L 851 809 L 916 838"
              fill={C.blue}
            />
            <rect y="1392" width="1080" height="528" fill="#DDE4E7" />
            <Dude
              frame={frame}
              x={375}
              y={875}
              mood={perfect ? "smug" : "study"}
              hand
              talk
            />
            <g transform={`translate(811 1123) rotate(0)`}>
              <path
                d="M 0 -222 L 0 -332 L 72 -339 Q 74 -281 128 -258 L 132 -222 Z"
                fill={C.blue}
                stroke={C.ink}
                strokeWidth="9"
              />
              <path d="M 30 -332 L 28 -445" stroke={C.ink} strokeWidth="14" />
              <path
                d="M -61 -218 Q 9 -285 136 -226 L 108 -161 L -51 -160 Z"
                fill={C.coral}
                stroke={C.ink}
                strokeWidth="9"
              />
              <path
                d="M 32 -154 L 32 228 L -30 249 L 124 249 L 62 228 L 62 -154"
                fill={C.ink}
              />
            </g>
            <rect
              x="35"
              y="1380"
              width="1010"
              height="69"
              rx="15"
              fill={C.paper}
              stroke={C.ink}
              strokeWidth="12"
            />
            <path
              d="M 170 1450 L 170 1920 M 930 1450 L 930 1920"
              stroke={C.ink}
              strokeWidth="25"
            />
          </>
        )}
        {mode === 1 && (
          <>
            <rect
              x="574"
              y="590"
              width="411"
              height="766"
              rx="130"
              fill="#BAF2E6"
              stroke={C.ink}
              strokeWidth="17"
            />
            <path
              d="M 646 699 L 858 1255 M 710 669 L 922 1225"
              stroke={C.paper}
              strokeWidth="23"
              opacity=".5"
            />
            <Dude
              frame={frame}
              x={281}
              y={881}
              mood={perfect ? "smug" : "panic"}
              hand
              talk
            />
            <Dude frame={frame} x={782} y={934} scale={0.58} mood="smug" hand />
          </>
        )}
        {mode === 2 && (
          <>
            <Dude
              frame={frame}
              x={540}
              y={925}
              mood={perfect ? "smug" : "panic"}
              scale={1.15}
              hand
              talk
            />
            <path d="M 212 1324 L 868 1324" stroke={C.ink} strokeWidth="26" />
            <rect
              x="179"
              y="1254"
              width="84"
              height="142"
              rx="16"
              fill={C.yellow}
              stroke={C.ink}
              strokeWidth="12"
            />
            <rect
              x="817"
              y="1254"
              width="84"
              height="142"
              rx="16"
              fill={C.yellow}
              stroke={C.ink}
              strokeWidth="12"
            />
          </>
        )}
        {mode === 0 && (
          <g>
            <ellipse
              cx="791"
              cy="449"
              rx="161"
              ry="112"
              fill={C.paper}
              stroke={C.ink}
              strokeWidth="9"
            />
            <circle
              cx="608"
              cy="570"
              r="25"
              fill={C.paper}
              stroke={C.ink}
              strokeWidth="7"
            />
            <circle
              cx="553"
              cy="632"
              r="15"
              fill={C.paper}
              stroke={C.ink}
              strokeWidth="5"
            />
            <Drink
              kind={perfect ? "beer" : "water"}
              x={791}
              y={542}
              scale={0.57}
              frame={frame}
            />
          </g>
        )}
        <g
          transform={`translate(${mode === 0 ? 467 : 530} ${mode === 0 ? 1305 : 1318}) rotate(${mode === 0 ? -6 : 3})`}
        >
          <rect
            x="-298"
            y="-97"
            width="595"
            height="194"
            fill={C.paper}
            stroke={C.ink}
            strokeWidth="12"
          />
          <text
            x="0"
            y="-19"
            fill={C.ink}
            fontSize="61"
            fontWeight="800"
            textAnchor="middle"
            fontFamily="Inter"
          >
            Vodu, molim.
          </text>
          <text
            x="0"
            y="47"
            fill={C.blue}
            fontSize="39"
            fontWeight="600"
            textAnchor="middle"
            fontFamily="Inter"
          >
            Воды, пожалуйста.
          </text>
        </g>
        {perfect && (
          <g transform={`translate(828 972) rotate(-14) scale(${pop(frame)})`}>
            <circle r="118" fill={C.coral} stroke={C.ink} strokeWidth="12" />
            <text
              x="0"
              y="22"
              fill={C.paper}
              fontSize="66"
              fontFamily="Inter"
              fontWeight="800"
              textAnchor="middle"
            >
              10/10
            </text>
          </g>
        )}
      </SVG>
    </>
  );
};

const Brain: React.FC<{ frame: number }> = ({ frame }) => (
  <>
    <SVG>
      <rect width="1080" height="1920" fill={C.paper} />
      <Dude frame={frame} x={540} y={1250} scale={1.75} mood="panic" />
      <g transform={`translate(344 798) rotate(${-12 + Math.sin(frame) * 3})`}>
        <rect
          x="-219"
          y="-70"
          width="438"
          height="140"
          fill={C.paper}
          stroke={C.ink}
          strokeWidth="12"
        />
        <text
          textAnchor="middle"
          y="22"
          fill={C.ink}
          fontSize="63"
          fontWeight="800"
          fontFamily="Inter"
        >
          VODU
        </text>
      </g>
      <g
        transform={`translate(761 658) rotate(${12 + Math.sin(frame + 2) * 4})`}
      >
        <rect
          x="-207"
          y="-70"
          width="414"
          height="140"
          fill={C.yellow}
          stroke={C.ink}
          strokeWidth="12"
        />
        <text
          textAnchor="middle"
          y="22"
          fill={C.ink}
          fontSize="63"
          fontWeight="800"
          fontFamily="Inter"
        >
          PIVO
        </text>
      </g>
      <path
        d="M 428 899 Q 205 578 642 523 L 626 486 M 623 486 L 680 523 L 641 563"
        fill="none"
        stroke={C.ink}
        strokeWidth="15"
      />
    </SVG>
    <Title text={"В голове:\nдва файла."} />
    <Label text="Названия почти совпали." color={C.paper} top={1610} />
  </>
);

const Payoff: React.FC<{ frame: number; variant: string }> = ({
  frame,
  variant,
}) => (
  <>
    <CallScreen
      frame={frame}
      shock={variant === "a"}
      sad={variant === "b"}
      drink={variant === "a" ? "beer" : "water"}
    />
    <Title text={COPY[variant === "a" ? "a" : "b"].joke} small />
    <Subtitle
      text={
        variant === "a"
          ? "Вода, ты изменилась."
          : "Заказ совпал с фразой. Но не с желанием."
      }
      top={1670}
    />
  </>
);

const Phone: React.FC<{ frame: number; variant: string; ending?: boolean }> = ({
  frame,
  variant,
  ending,
}) => {
  const beer = variant === "b";
  const agentActive = frame >= 45;
  const input = COPY[beer ? "b" : "a"].goal;
  return (
    <>
      <SVG>
        <rect width="1080" height="1920" fill={C.paper} />
      </SVG>
      <Title
        text={
          ending
            ? "Задай цель по-русски.\nАгент скажет по телефону."
            : "Напиши, чего хочешь."
        }
        small
        top={151}
      />
      <div
        className="phone"
        style={{
          transform: `translateY(${clamp(frame, 0, 14, 90, 0)}px) scale(${ending ? 1 : clamp(frame, 46, 75, 1, 1.045)})`,
        }}
      >
        <div className="island" />
        <div className="app-head">
          <svg width="69" height="69" viewBox="0 0 80 80">
            <rect width="80" height="80" rx="20" fill={C.blue} />
            <path
              d="M 16 41 L 16 48 M 29 26 L 29 55 M 42 18 L 42 62 M 55 31 L 55 51 M 68 36 L 68 47"
              stroke={C.paper}
              strokeWidth="7"
              strokeLinecap="round"
            />
          </svg>
          <span>Call Translate</span>
        </div>
        <div className="phone-caption">Звонок: доставка напитков</div>
        <div className="user-line">
          <span className="who">Приватная задача агенту</span>
          <strong>
            {frame < 29
              ? input.slice(
                  0,
                  Math.max(0, Math.floor(((frame - 5) / 24) * input.length)),
                )
              : input}
            <span
              className="cursor"
              style={{ opacity: frame < 34 && frame % 12 < 8 ? 1 : 0 }}
            >
              |
            </span>
          </strong>
          <div className="private-hint">Текст. Собеседник его не слышит.</div>
        </div>
        <div
          className="agent-line"
          style={{
            opacity: pop(frame, 45),
            transform: `scale(${Math.max(0.01, pop(frame, 45))})`,
          }}
        >
          <span className="who">Агент говорит в звонок</span>
          <strong>{COPY[beer ? "b" : "a"].phrase}</strong>
          <span>{COPY[beer ? "b" : "a"].translation}</span>
          <div className="agent-wave">
            {Array.from({ length: 25 }, (_, i) => (
              <span
                key={i}
                style={{
                  height:
                    agentActive && !ending
                      ? 12 + Math.abs(Math.sin((frame + i * 7) * 0.3)) * 54
                      : 12,
                }}
              />
            ))}
          </div>
        </div>
        <div className="concept">
          Концепт голосового агента
          <br />
          Call Translate + ElevenLabs
        </div>
      </div>
      <Subtitle
        text={
          ending
            ? "Call Translate"
            : beer
              ? "Задача остается приватной.\nВ звонок идет только сербская фраза."
              : "В звонок: Vodu, molim."
        }
        top={1650}
      />
    </>
  );
};

const End: React.FC<{ frame: number; variant: string }> = ({ variant }) => (
  <Phone frame={99} variant={variant} ending />
);

const Score: React.FC<{ frame: number; variant: string }> = ({
  frame,
  variant,
}) => (
  <>
    <CallScreen
      frame={frame + 30}
      drink={variant === "a" ? "beer" : "water"}
      shock={variant === "a"}
      sad={variant === "b"}
    />
    <Title text={COPY[variant === "a" ? "a" : "b"].score} small />
    <Subtitle
      text={variant === "a" ? "Мозг выбрал happy hour." : "Я же хотел пиво..."}
      top={1660}
    />
  </>
);

const Content: React.FC<{ variant: string }> = ({ variant }) => {
  const frame = useCurrentFrame();
  if (variant === "a") {
    if (frame < TIMING.a.montage)
      return (
        <>
          <Study frame={frame} variant={variant} />
          <Title text={COPY.a.hook} small />
          <Subtitle text="Пожалуйста, дайте воды." />
        </>
      );
    if (frame < TIMING.a.call) {
      const f = frame - TIMING.a.montage;
      const mode = Math.floor(f / 28);
      return (
        <>
          <Study frame={f % 28} variant={variant} mode={mode} />
          <Title
            text={["Еще раз.", "Перед зеркалом.", "47-я репетиция."][mode]}
          />
          <Label
            text={["Vodu, molim.", "Vodu, molim.", "Vodu. Molim."][mode]}
            top={1580}
            color={C.paper}
          />
        </>
      );
    }
    if (frame < TIMING.a.brain)
      return (
        <>
          <CallScreen frame={frame - TIMING.a.call} />
          <Title text={"Звонок\nв доставку."} small />
          <Subtitle text="Внутри: мне воды. Воды." top={1670} />
        </>
      );
    if (frame < TIMING.a.order) return <Brain frame={frame - TIMING.a.brain} />;
    if (frame < TIMING.a.payoff)
      return (
        <>
          <CallScreen frame={frame - TIMING.a.order} speaking drink="beer" />
          <Title text="Pivo, molim." />
          <div className="telephone-context">Звонок в доставку напитков</div>
          <Subtitle text="Пиво, пожалуйста." />
        </>
      );
    if (frame < TIMING.a.score)
      return <Payoff frame={frame - TIMING.a.payoff} variant={variant} />;
    if (frame < TIMING.a.task)
      return <Score frame={frame - TIMING.a.score} variant={variant} />;
    if (frame < TIMING.a.end)
      return <Phone frame={frame - TIMING.a.task} variant={variant} />;
    return <End frame={frame - TIMING.a.end} variant={variant} />;
  }
  if (frame < TIMING.b.montage)
    return (
      <>
        <Study frame={frame} variant={variant} />
        <Title text={COPY.b.hook} />
        <Subtitle text="Сейчас позвоню в доставку." />
      </>
    );
  if (frame < TIMING.b.order) {
    const f = frame - TIMING.b.montage;
    const mode = Math.floor(f / 24);
    return (
      <>
        <Study frame={f % 24} variant={variant} mode={mode} />
        <Title text={["Без акцента.", "Без запинки.", "Без шансов."][mode]} />
        <Label text="Vodu, molim." top={1570} color={C.paper} />
      </>
    );
  }
  if (frame < TIMING.b.payoff)
    return (
      <>
        <CallScreen frame={frame - TIMING.b.order} speaking drink="water" />
        <Title text="Vodu, molim." />
        <div className="telephone-context">Звонок в доставку напитков</div>
        <Subtitle text="Воды, пожалуйста." />
      </>
    );
  if (frame < TIMING.b.score)
    return <Payoff frame={frame - TIMING.b.payoff} variant={variant} />;
  if (frame < TIMING.b.task)
    return <Score frame={frame - TIMING.b.score} variant={variant} />;
  if (frame < TIMING.b.end)
    return <Phone frame={frame - TIMING.b.task} variant={variant} />;
  return <End frame={frame - TIMING.b.end} variant={variant} />;
};

export const Meme: React.FC<{ variant: string }> = ({ variant }) => (
  <AbsoluteFill
    style={{
      backgroundColor: C.paper,
      fontFamily: "Inter",
      ...Object.fromEntries(
        Object.entries(C).map(([key, value]) => ["--" + key, value]),
      ),
    }}
  >
    <Content variant={variant} />
    <Audio src={staticFile(`audio/mix-${variant}.wav`)} />
  </AbsoluteFill>
);
