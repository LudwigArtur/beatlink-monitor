import type { BandGains } from './eq';

let scratch: HTMLCanvasElement | null = null;

const channel = (value: number) => Math.round(Math.min(1, Math.max(0, value)) * 255);

/**
 * Applies per-band EQ gains to an already drawn RGB waveform (its red / green / blue components stand for
 * the low / mid / high content) inside the rectangle x, y, w, h: factors below 1 dim the component by
 * multiplication, the part above 1 adds a copy of the original waveform, scaled per component, on top (glow).
 * [redrawSource] must draw the undimmed waveform into the given context, in the same coordinates as [ctx].
 */
export function applyRgbEq(
  ctx: CanvasRenderingContext2D,
  x: number,
  y: number,
  w: number,
  h: number,
  gains: BandGains,
  redrawSource: (target: CanvasRenderingContext2D) => void,
) {
  const boost = { low: Math.max(0, gains.low - 1), mid: Math.max(0, gains.mid - 1), high: Math.max(0, gains.high - 1) };
  const hasBoost = boost.low + boost.mid + boost.high > 0;
  const dims = gains.low < 1 || gains.mid < 1 || gains.high < 1;

  let glow: HTMLCanvasElement | null = null;
  if (hasBoost) {
    scratch ??= document.createElement('canvas');
    scratch.width = Math.max(1, Math.ceil(w));
    scratch.height = Math.max(1, Math.ceil(h));
    const sctx = scratch.getContext('2d');
    if (sctx) {
      sctx.setTransform(1, 0, 0, 1, -x, -y);
      redrawSource(sctx);
      sctx.setTransform(1, 0, 0, 1, 0, 0);
      sctx.globalCompositeOperation = 'multiply';
      sctx.fillStyle = `rgb(${channel(boost.low)}, ${channel(boost.mid)}, ${channel(boost.high)})`;
      sctx.fillRect(0, 0, scratch.width, scratch.height);
      glow = scratch;
    }
  }

  if (dims) {
    ctx.save();
    ctx.globalCompositeOperation = 'multiply';
    ctx.fillStyle = `rgb(${channel(gains.low)}, ${channel(gains.mid)}, ${channel(gains.high)})`;
    ctx.fillRect(x, y, w, h);
    ctx.restore();
  }
  if (glow) {
    ctx.save();
    ctx.globalCompositeOperation = 'lighter';
    ctx.drawImage(glow, x, y);
    ctx.restore();
  }
}

/** True if any band deviates from unchanged brightness. */
export function eqChangesBrightness(gains: BandGains | undefined): gains is BandGains {
  return gains != null && (gains.low !== 1 || gains.mid !== 1 || gains.high !== 1);
}
