import React from 'react';
import {Composition, registerRoot} from 'remotion';
import {Meme} from './Meme';
import {TIMING} from './content';
const Root: React.FC = () => <><Composition id="WaterToBeer" component={Meme} durationInFrames={TIMING.a.duration} fps={30} width={1080} height={1920} defaultProps={{variant: 'a'}}/><Composition id="PerfectPhraseWrongWish" component={Meme} durationInFrames={TIMING.b.duration} fps={30} width={1080} height={1920} defaultProps={{variant: 'b'}}/></>;
registerRoot(Root);
