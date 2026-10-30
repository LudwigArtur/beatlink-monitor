<script lang="ts">
  import MixerFader from './MixerFader.svelte';
  import MixerKnob from './MixerKnob.svelte';
  import MixerMeter from './MixerMeter.svelte';
  import type { AudioLevelStatus, MixerLiveState, MixerState } from '../stores/types';

  /** Live knob and fader positions, the model facts of the mixer (see MixerModels in the backend) and optional levels. */
  export let live: MixerLiveState;
  export let mixer: MixerState;
  export let levels: AudioLevelStatus['levels'] | null = null;

  const STRIP = 88;
  const X0 = 8;
  const FADER_HEIGHT = 150;

  $: count = mixer.channelCount ?? live.channels.length;
  $: channels = live.channels.slice(0, count);
  $: fourBand = mixer.eqBands === 4;
  $: eqRows = (fourBand
    ? [['eqHi', 'HI'], ['eqHiMid', 'HI MID'], ['eqLoMid', 'LO MID'], ['eqLow', 'LOW']]
    : [['eqHi', 'HI'], ['eqHiMid', 'MID'], ['eqLow', 'LOW']]) as ['eqHi' | 'eqHiMid' | 'eqLoMid' | 'eqLow', string][];
  $: hasComp = mixer.hasComp ?? false;
  $: hasSend = mixer.hasSend ?? false;
  $: hasIsolator = mixer.hasIsolator ?? false;
  $: filterName = mixer.filterName ?? 'FILTER';

  // Vertical layout of a channel strip, top to bottom.
  const yTitle = 14;
  const yTrim = 50;
  const yEq0 = 100;
  const eqStep = 50;
  $: yEqLast = yEq0 + (eqRows.length - 1) * eqStep;
  $: yFilter = yEqLast + 56;
  $: ySend = yFilter + 52;
  $: yCue = (hasSend ? ySend : yFilter) + 42;
  $: yAssign = yCue + 26;
  $: faderTop = yAssign + 20;
  $: faderBottom = faderTop + FADER_HEIGHT;
  $: yCross = faderBottom + 56;
  $: height = yCross + 26;
  $: meterTop = yEq0 - 14;
  $: meterHeight = yEqLast + 22 - meterTop;

  // Columns to the right of the strips: master, optional isolator, beat FX.
  $: xm = X0 + count * STRIP + 12;
  $: xi = xm + 112;
  $: xf = xm + 112 + (hasIsolator ? 90 : 0);
  $: width = xf + 128;

  $: crossCenter = X0 + (count * STRIP) / 2;
  $: crossLength = Math.max(160, count * STRIP * 0.5);

  const assignName = (v: number) => (v === 1 ? 'A' : v === 2 ? 'B' : 'THRU');
  const assignSlot = (v: number) => (v === 1 ? 0 : v === 2 ? 2 : 1);
  const fxChannelName = (v: number) => (v <= 5 ? `CH ${v + 1}` : v === 6 ? 'MIC' : v === 7 ? 'MASTER' : v === 8 ? 'CF A' : v === 9 ? 'CF B' : `#${v}`);
  const onOff = (v: boolean) => (v ? 'on' : 'off');
</script>

<svg
  class="mixer"
  viewBox={`0 0 ${width} ${height}`}
  role="img"
  aria-label={`${mixer.model ?? 'Mixer'} controls`}
  preserveAspectRatio="xMidYMin meet"
>
  <!-- Channel strips -->
  {#each channels as channel, i (channel.channel)}
    {@const sx = X0 + i * STRIP}
    {@const mid = sx + STRIP / 2}
    <rect class="strip" x={sx + 2} y="2" width={STRIP - 4} height={faderBottom + 20} rx="4" />
    <text class="title" x={mid} y={yTitle}>{channel.channel}</text>

    <MixerKnob cx={sx + (hasComp ? 26 : STRIP / 2)} cy={yTrim} value={channel.trim} label="TRIM" tip={`Ch ${channel.channel} trim: ${channel.trim}`} />
    {#if hasComp}
      <MixerKnob cx={sx + 62} cy={yTrim} value={channel.comp} label="COMP" tip={`Ch ${channel.channel} comp: ${channel.comp}`} />
    {/if}

    <MixerMeter x={sx + 8} y={meterTop} width={7} height={meterHeight} level={levels ? (levels.channels[i]?.left ?? 0) : null} label={`Ch ${channel.channel} level`} />
    {#each eqRows as [key, label], row}
      <MixerKnob
        cx={sx + 56}
        cy={yEq0 + row * eqStep}
        value={channel[key]}
        centered
        {label}
        tip={`Ch ${channel.channel} EQ ${label}: ${channel[key]}${channel[key] === 128 ? ' (center)' : ''}`}
      />
    {/each}

    <MixerKnob cx={mid} cy={yFilter} radius={15} value={channel.filter} centered label={filterName} tip={`Ch ${channel.channel} ${filterName.toLowerCase()}: ${channel.filter}`} />
    {#if hasSend}
      <MixerKnob cx={mid} cy={ySend} value={channel.send} label="SEND" tip={`Ch ${channel.channel} send: ${channel.send}`} />
    {/if}

    <g>
      <title>{`Ch ${channel.channel} cue A: ${onOff(channel.cue)}`}</title>
      <circle class="lamp" class:on={channel.cue} cx={sx + 28} cy={yCue} r="6" />
      <text class="small" x={sx + 28} y={yCue + 16}>CUE A</text>
    </g>
    <g>
      <title>{`Ch ${channel.channel} cue B: ${onOff(channel.cue2 > 0)}`}</title>
      <circle class="lamp" class:on={channel.cue2 > 0} cx={sx + 60} cy={yCue} r="6" />
      <text class="small" x={sx + 60} y={yCue + 16}>B</text>
    </g>

    <g>
      <title>{`Ch ${channel.channel} crossfader assign: ${assignName(channel.crossfaderAssign)}`}</title>
      <rect class="switch" x={mid - 26} y={yAssign + 2} width="52" height="10" rx="5" />
      <circle class="knob-dot" cx={mid - 17 + assignSlot(channel.crossfaderAssign) * 17} cy={yAssign + 7} r="4" />
      <text class="small" x={mid - 17} y={yAssign + 22}>A</text>
      <text class="small" x={mid} y={yAssign + 22}>THRU</text>
      <text class="small" x={mid + 17} y={yAssign + 22}>B</text>
    </g>

    <MixerFader x={mid} y={faderTop + 10} height={FADER_HEIGHT - 10} value={channel.fader} label={String(channel.channel)} tip={`Ch ${channel.channel} fader: ${channel.fader}`} />
  {/each}

  <!-- Master, booth and headphones -->
  <text class="title" x={xm + 56} y={yTitle}>MASTER</text>
  <MixerKnob cx={xm + 28} cy={yTrim} value={live.masterLevel} label="LEVEL" tip={`Master level: ${live.masterLevel}`} />
  <MixerMeter x={xm + 68} y={meterTop} width={10} height={meterHeight} level={levels ? levels.masterLeft : null} label="Master L" />
  <MixerMeter x={xm + 84} y={meterTop} width={10} height={meterHeight} level={levels ? levels.masterRight : null} label="Master R" />
  <text class="small" x={xm + 73} y={meterTop + meterHeight + 12}>L</text>
  <text class="small" x={xm + 89} y={meterTop + meterHeight + 12}>R</text>
  {#if hasIsolator}
    <MixerKnob cx={xm + 28} cy={yFilter} centered value={live.masterFilter} label="FILTER" tip={`Master filter: ${live.masterFilter}`} />
  {/if}
  <g>
    <title>{`Master cue: ${onOff(live.masterCue !== 0)}`}</title>
    <circle class="lamp" class:on={live.masterCue !== 0} cx={xm + 28} cy={yCue} r="6" />
    <text class="small" x={xm + 28} y={yCue + 16}>CUE</text>
  </g>

  <text class="caption" x={xm + 56} y={faderBottom - 138}>BOOTH</text>
  <MixerKnob cx={xm + 22} cy={faderBottom - 114} radius={11} centered value={live.boothHi} label="HI" tip={`Booth EQ hi: ${live.boothHi}`} />
  <MixerKnob cx={xm + 56} cy={faderBottom - 114} radius={11} centered value={live.boothLow} label="LOW" tip={`Booth EQ low: ${live.boothLow}`} />
  <MixerKnob cx={xm + 90} cy={faderBottom - 114} radius={11} value={live.booth} label="LEVEL" tip={`Booth level: ${live.booth}`} />
  <text class="caption" x={xm + 56} y={faderBottom - 66}>HEADPHONES</text>
  <MixerKnob cx={xm + 32} cy={faderBottom - 42} radius={11} centered value={live.hpMix} label="MIX" tip={`Headphones mix: ${live.hpMix} (cue … master)`} />
  <MixerKnob cx={xm + 80} cy={faderBottom - 42} radius={11} value={live.hpLevel} label="LEVEL" tip={`Headphones level: ${live.hpLevel}`} />

  <!-- Isolator (models that have one) -->
  {#if hasIsolator}
    <text class="title" x={xi + 40} y={yTitle}>ISOLATOR</text>
    <g>
      <title>{`Isolator: ${onOff(live.isolatorOn !== 0)}`}</title>
      <circle class="lamp" class:on={live.isolatorOn !== 0} cx={xi + 40} cy="36" r="6" />
      <text class="small" x={xi + 40} y="52">ON</text>
    </g>
    <MixerKnob cx={xi + 40} cy={88} radius={17} centered value={live.isoHi} label="HI" tip={`Isolator hi: ${live.isoHi}`} />
    <MixerKnob cx={xi + 40} cy={144} radius={17} centered value={live.isoMid} label="MID" tip={`Isolator mid: ${live.isoMid}`} />
    <MixerKnob cx={xi + 40} cy={200} radius={17} centered value={live.isoLow} label="LOW" tip={`Isolator low: ${live.isoLow}`} />
  {/if}

  <!-- Beat FX -->
  <text class="title" x={xf + 60} y={yTitle}>BEAT FX</text>
  <g>
    <title>{`Beat FX select: ${live.fxSelect}`}</title>
    <rect class="screen" x={xf + 8} y="28" width="104" height="34" rx="3" />
    <text class="screen-main" x={xf + 60} y="50">FX {live.fxSelect}</text>
  </g>
  <g>
    <title>{`Beat FX channel: ${fxChannelName(live.fxChannel)}`}</title>
    <rect class="screen" x={xf + 8} y="68" width="104" height="22" rx="3" />
    <text class="screen-sub" x={xf + 60} y="83">{fxChannelName(live.fxChannel)}</text>
  </g>
  {#each [['LOW', live.fxFreqLow], ['MID', live.fxFreqMid], ['HI', live.fxFreqHi]] as [name, on], k}
    <g>
      <title>{`FX frequency ${name}: ${onOff(on === 1)}`}</title>
      <circle class="lamp" class:on={on === 1} cx={xf + 24 + k * 36} cy="116" r="6" />
      <text class="small" x={xf + 24 + k * 36} y="132">{name}</text>
    </g>
  {/each}
  <MixerKnob cx={xf + 36} cy={172} radius={15} value={live.fxLevel} label="LEVEL/DEPTH" tip={`Beat FX level/depth: ${live.fxLevel}`} />
  <g>
    <title>{`Beat FX: ${onOff(live.fxOn === 1)}`}</title>
    <circle class="lamp big" class:on={live.fxOn === 1} cx={xf + 90} cy="172" r="10" />
    <text class="small" x={xf + 90} y="196">ON/OFF</text>
  </g>

  <!-- Crossfader -->
  <text class="caption" x={crossCenter} y={yCross - 16}>CROSSFADER</text>
  <text class="small" x={crossCenter - crossLength / 2 - 12} y={yCross + 3}>A</text>
  <text class="small" x={crossCenter + crossLength / 2 + 12} y={yCross + 3}>B</text>
  <MixerFader x={crossCenter} y={yCross} height={crossLength} value={live.crossfader} horizontal tip={`Crossfader: ${live.crossfader}`} />
</svg>

<style>
  .mixer {
    display: block;
    width: 100%;
    height: auto;
    font-family: system-ui, -apple-system, 'Segoe UI', sans-serif;
  }
  .strip {
    fill: none;
    stroke: var(--border);
    stroke-width: 1;
    vector-effect: non-scaling-stroke;
  }
  .title {
    fill: var(--text);
    font-size: 11px;
    font-weight: 600;
    text-anchor: middle;
  }
  .caption {
    fill: var(--muted);
    font-size: 8.5px;
    font-weight: 600;
    letter-spacing: 0.06em;
    text-anchor: middle;
  }
  .small {
    fill: var(--muted);
    font-size: 7.5px;
    text-anchor: middle;
  }
  .lamp {
    fill: var(--bg);
    stroke: var(--border-strong);
    stroke-width: 1;
    vector-effect: non-scaling-stroke;
  }
  .lamp.on {
    fill: var(--accent);
    stroke: var(--accent);
  }
  .switch {
    fill: var(--bg);
    stroke: var(--border-strong);
    stroke-width: 1;
    vector-effect: non-scaling-stroke;
  }
  .knob-dot {
    fill: var(--text);
  }
  .screen {
    fill: var(--bg);
    stroke: var(--border-strong);
    stroke-width: 1;
    vector-effect: non-scaling-stroke;
  }
  .screen-main {
    fill: var(--text);
    font-size: 16px;
    font-weight: 600;
    text-anchor: middle;
  }
  .screen-sub {
    fill: var(--muted);
    font-size: 11px;
    text-anchor: middle;
  }
</style>
