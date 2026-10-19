import type { PlayerSlotConfig, UiProfile, WidgetConfig } from './types';

export const DEFAULT_GLOBAL_WIDGET_IDS = [
  'masterBpm',
  'masterPlayer',
  'networkStatus',
  'prodjlinkStatus',
  'linkStatus',
  'grandmaStatus',
  'simulatorStatus',
] as const;

export function defaultWidgets(): WidgetConfig[] {
  return DEFAULT_GLOBAL_WIDGET_IDS.map((id, index) => ({
    id,
    type: id,
    visible: true,
    x: 0,
    y: 0,
    width: 200,
    height: 100,
    order: index,
    fontSize: 14,
    color: null,
  }));
}

export function defaultPlayerSlots(): PlayerSlotConfig[] {
  return [1, 2, 3, 4, 5, 6].map((slot) => ({
    slot,
    sourceType: 'PLAYER',
    playerNumber: slot,
    visible: slot <= 4,
  }));
}

export function defaultProfile(name = 'default'): UiProfile {
  return {
    configVersion: 1,
    name,
    widgets: defaultWidgets(),
    playerSlots: defaultPlayerSlots(),
    globalScale: 1,
    fullscreen: false,
    backgroundColor: '#101014',
    inactiveDeckBrightnessPercent: 50,
    waveformSmoothingFactor: 0.08,
  };
}
