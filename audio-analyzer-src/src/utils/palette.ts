import { ColorPalette } from '../types';

export function getColorForValue(v: number, palette: ColorPalette): string {
  const clamped = Math.max(0, Math.min(1, v));

  switch (palette) {
    case 'FIRE': {
      // Black -> Red -> Orange -> Yellow -> White
      if (clamped < 0.25) {
        const t = clamped / 0.25;
        return `rgb(${Math.round(t * 180)}, 0, 0)`;
      } else if (clamped < 0.6) {
        const t = (clamped - 0.25) / 0.35;
        return `rgb(255, ${Math.round(t * 165)}, 0)`;
      } else if (clamped < 0.85) {
        const t = (clamped - 0.6) / 0.25;
        return `rgb(255, ${Math.round(165 + t * 90)}, ${Math.round(t * 100)})`;
      } else {
        const t = (clamped - 0.85) / 0.15;
        return `rgb(255, 255, ${Math.round(100 + t * 155)})`;
      }
    }
    case 'RAINBOW': {
      // HSV hue 280 (purple) to 0 (red)
      const hue = (1.0 - clamped) * 280;
      return `hsl(${hue}, 100%, 55%)`;
    }
    case 'CYBERPUNK': {
      // Deep purple/magenta -> Pink #FF007F -> Electric Yellow #FFE600
      if (clamped < 0.5) {
        const t = clamped / 0.5;
        const r = Math.round(120 + t * 135);
        const g = Math.round(t * 30);
        const b = Math.round(200 - t * 73);
        return `rgb(${r}, ${g}, ${b})`;
      } else {
        const t = (clamped - 0.5) / 0.5;
        const r = 255;
        const g = Math.round(t * 230);
        const b = Math.round(127 * (1 - t));
        return `rgb(${r}, ${g}, ${b})`;
      }
    }
    case 'MONOCHROME': {
      const c = Math.round(clamped * 255);
      return `rgb(${c}, ${c}, ${c})`;
    }
    case 'NEON':
    default: {
      // Deep Blue -> Cyan #00F0FF -> Bright Mint -> Electric Violet
      if (clamped < 0.5) {
        const t = clamped / 0.5;
        const r = Math.round(0 + t * 0);
        const g = Math.round(100 + t * 140);
        const b = 255;
        return `rgb(${r}, ${g}, ${b})`;
      } else {
        const t = (clamped - 0.5) / 0.5;
        const r = Math.round(t * 180);
        const g = Math.round(240 - t * 80);
        const b = 255;
        return `rgb(${r}, ${g}, ${b})`;
      }
    }
  }
}

export function getRgbNumeric(v: number, palette: ColorPalette): [number, number, number] {
  const clamped = Math.max(0, Math.min(1, v));
  switch (palette) {
    case 'FIRE':
      return [clamped, clamped * 0.7, clamped * 0.1];
    case 'RAINBOW': {
      const hue = (1.0 - clamped) * 0.77;
      // Convert HSV to RGB
      const i = Math.floor(hue * 6);
      const f = hue * 6 - i;
      const q = 1 - f;
      const t = f;
      switch (i % 6) {
        case 0: return [1, t, 0];
        case 1: return [q, 1, 0];
        case 2: return [0, 1, t];
        case 3: return [0, q, 1];
        case 4: return [t, 0, 1];
        case 5: return [1, 0, q];
        default: return [1, 1, 1];
      }
    }
    case 'CYBERPUNK':
      return [clamped, (1 - clamped) * 0.8, 0.9];
    case 'MONOCHROME':
      return [clamped, clamped, clamped];
    case 'NEON':
    default:
      return [clamped * 0.6, 0.9 - clamped * 0.3, 1.0];
  }
}
