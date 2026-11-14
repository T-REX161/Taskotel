import React, { useState } from 'react';
import { avatarInitials } from '../../utils/profilePicture';

/**
 * The shared avatar for every person shown in Taskotel: their uploaded profile picture, or their initials when there is no picture or it
 * cannot be loaded. Always a circle; the photo is cropped to fill it.
 *
 * Props:
 *  - imageUrl: the person's existing profile picture URL (profileImageUrl / staffProfileImageUrl from the API), or null/undefined for none
 *  - name: whose picture it is (alt text, and the initials when `initials` is not given)
 *  - initials: text for the default avatar (default: avatarInitials(name), e.g. "Sofia Marino" -> "SM", "rahul" -> "RA")
 *  - size: one of AVATAR_SIZES (xs 28px, sm 32px, md 36px, lg 40px, xl 44px, 2xl 48px); or give the size in className instead
 *  - className: size, border, ring or other classes shared by the picture and the default avatar (e.g. a "selected" ring)
 *  - fallbackClassName: colours and font of the default avatar only (default: the soft emerald initials style)
 *  - photoClassName: classes for the picture only (e.g. its border colour, or a "selected" ring that only a photo needs)
 */
export const AVATAR_SIZES = {
  xs: 'w-7 h-7 text-[11px]',
  sm: 'w-8 h-8 text-xs',
  md: 'w-9 h-9 text-xs',
  lg: 'w-10 h-10 text-sm',
  xl: 'w-11 h-11 text-sm',
  '2xl': 'w-12 h-12 text-base',
};

const DEFAULT_FALLBACK = 'bg-emerald-50 text-emerald-700 ring-1 ring-inset ring-emerald-100 font-semibold';

export default function UserAvatar({ imageUrl, initials, name, size, className = '', fallbackClassName = DEFAULT_FALLBACK, photoClassName = '' }) {
  // The picture URL that failed to load. Remembering the URL (rather than a yes/no flag that an effect resets) means a failure always
  // sticks, even one that happens at once (blocked, offline or a cached error) before any reset could run, and a new picture still gets
  // a fresh chance to load.
  const [failedUrl, setFailedUrl] = useState(null);
  const failed = Boolean(imageUrl) && failedUrl === imageUrl;
  const shape = `${size ? AVATAR_SIZES[size] || '' : ''} rounded-full shrink-0 ${className}`.trim();

  if (imageUrl && !failed) {
    return (
      <img
        src={imageUrl}
        alt={name ? `${name}'s profile picture` : 'Profile picture'}
        className={`${shape} ${photoClassName} object-cover`}
        loading="lazy"
        decoding="async"
        referrerPolicy="no-referrer"
        data-avatar="photo"
        onError={() => setFailedUrl(imageUrl)}
      />
    );
  }

  return (
    <div className={`${shape} ${fallbackClassName} flex items-center justify-center select-none`} aria-hidden="true" data-avatar="initials">
      {initials ?? avatarInitials(name)}
    </div>
  );
}
