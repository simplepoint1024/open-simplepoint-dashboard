/** Keep a bottom configuration panel reachable even in a short viewport. */
export function drawerHeightBounds(viewportHeight: number, minimum = 240) {
  const maximum = Math.max(80, viewportHeight - 80);
  return {minimum: Math.min(minimum, maximum), maximum};
}

export function clampDrawerHeight(height: number, viewportHeight: number, minimum = 240) {
  const bounds = drawerHeightBounds(viewportHeight, minimum);
  return Math.min(bounds.maximum, Math.max(bounds.minimum, height));
}

/** Leave the right inspector inside even a narrow viewport. */
export function drawerWidthBounds(viewportWidth: number, minimum = 320) {
  const maximum = Math.max(80, viewportWidth - 24);
  return {minimum: Math.min(minimum, maximum), maximum};
}

export function clampDrawerWidth(width: number, viewportWidth: number, minimum = 320) {
  const bounds = drawerWidthBounds(viewportWidth, minimum);
  return Math.min(bounds.maximum, Math.max(bounds.minimum, width));
}
