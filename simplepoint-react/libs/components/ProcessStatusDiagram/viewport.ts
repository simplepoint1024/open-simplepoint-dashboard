/** Refit only after real canvas-size changes; preserve user zoom during unrelated renders. */
export function observeDiagramViewport(container: HTMLElement, refit: () => void): () => void {
  let width = -1, height = -1, frame = 0, disposed = false;
  const observer = new ResizeObserver(entries => {
    const size = entries[0]?.contentRect;
    if (disposed || !size) return;
    if (size.width <= 0 || size.height <= 0) {
      cancelAnimationFrame(frame); frame = 0; width = -1; height = -1;
      return;
    }
    if (size.width === width && size.height === height) return;
    width = size.width; height = size.height;
    cancelAnimationFrame(frame);
    frame = requestAnimationFrame(() => {
      frame = 0;
      if (disposed) return;
      // A retained tab or closing drawer can become hidden after the observation was queued.
      // Fitting its now-zero canvas would send a non-finite scale to the SVG matrix.
      if (!container.isConnected || container.clientWidth <= 0 || container.clientHeight <= 0) {
        width = -1; height = -1;
        return;
      }
      refit();
    });
  });
  observer.observe(container);
  return () => {disposed = true; observer.disconnect(); cancelAnimationFrame(frame);};
}
