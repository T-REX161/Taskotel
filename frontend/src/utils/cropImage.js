/**
 * Turning the crop chosen in the profile picture editor into a real image file.
 *
 * react-easy-crop reports the crop as a pixel rectangle in the image's own (natural) pixels; this draws exactly that rectangle onto a
 * square canvas and encodes it. Nothing here is a CSS effect: the file that is uploaded contains only the cropped, zoomed area.
 */

export const CROP_MIN_ZOOM = 1; // 1 = the image just covers the crop square, so it can never be smaller than the frame
export const CROP_MAX_ZOOM = 4;
export const CROP_OUTPUT_SIZE = 512; // the server stores 512 x 512, so a larger file would only be shrunk again
export const CROP_OUTPUT_TYPE = 'image/jpeg';
export const CROP_OUTPUT_QUALITY = 0.92;
export const CROP_OUTPUT_NAME = 'profile-picture.jpg';

/** Keeps a zoom level within the allowed range (and finite). */
export function clampZoom(zoom) {
  const value = Number(zoom);
  if (!Number.isFinite(value)) return CROP_MIN_ZOOM;
  return Math.min(CROP_MAX_ZOOM, Math.max(CROP_MIN_ZOOM, value));
}

/** Side of the output square: the cropped area's own size, never upscaled and never above CROP_OUTPUT_SIZE. */
export function outputSideFor(pixelCrop) {
  const side = Math.round(Math.min(pixelCrop?.width || 0, pixelCrop?.height || 0));
  if (!(side > 0)) return 0;
  return Math.min(side, CROP_OUTPUT_SIZE);
}

/** Loads an image for drawing. Remote pictures are requested with CORS so the canvas stays readable. */
export function loadImage(src) {
  return new Promise((resolve, reject) => {
    const image = new Image();
    if (!String(src).startsWith('blob:') && !String(src).startsWith('data:')) image.crossOrigin = 'anonymous';
    image.onload = () => resolve(image);
    image.onerror = () => reject(new Error('The image could not be loaded.'));
    image.src = src;
  });
}

/**
 * Draws the selected area (pixelCrop: { x, y, width, height } in the image's natural pixels) into a square JPEG file.
 * Transparent areas become white, since JPEG has no transparency.
 */
export async function getCroppedFile(imageSrc, pixelCrop) {
  const side = outputSideFor(pixelCrop);
  if (!side) throw new Error('There is no crop area to save.');
  const image = await loadImage(imageSrc);
  const canvas = document.createElement('canvas');
  canvas.width = side;
  canvas.height = side;
  const context = canvas.getContext('2d');
  if (!context) throw new Error('The image could not be processed in this browser.');
  context.fillStyle = '#ffffff';
  context.fillRect(0, 0, side, side);
  context.imageSmoothingQuality = 'high';
  context.drawImage(image, pixelCrop.x, pixelCrop.y, pixelCrop.width, pixelCrop.height, 0, 0, side, side);
  const blob = await new Promise((resolve) => canvas.toBlob(resolve, CROP_OUTPUT_TYPE, CROP_OUTPUT_QUALITY));
  if (!blob) throw new Error('The cropped image could not be created.');
  return new File([blob], CROP_OUTPUT_NAME, { type: CROP_OUTPUT_TYPE });
}
