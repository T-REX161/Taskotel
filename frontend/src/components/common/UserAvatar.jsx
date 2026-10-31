import React, { useEffect, useState } from 'react';

/**
 * A user's profile picture, or the default avatar (their initials) when there is no picture or it cannot be loaded.
 *
 * Props:
 *  - imageUrl: the picture's URL, or null/undefined for none
 *  - initials: text for the default avatar
 *  - name: whose picture it is (for the alt text)
 *  - className: size, shape and border, shared by the picture and the default avatar
 *  - fallbackClassName: colours and font of the default avatar only
 */
export default function UserAvatar({ imageUrl, initials, name, className = '', fallbackClassName = '' }) {
  const [failed, setFailed] = useState(false);

  // A new picture gets a fresh chance to load
  useEffect(() => {
    setFailed(false);
  }, [imageUrl]);

  if (imageUrl && !failed) {
    return (
      <img
        src={imageUrl}
        alt={name ? `${name}'s profile picture` : 'Profile picture'}
        className={`${className} object-cover`}
        loading="lazy"
        decoding="async"
        referrerPolicy="no-referrer"
        onError={() => setFailed(true)}
      />
    );
  }

  return (
    <div className={`${className} ${fallbackClassName} flex items-center justify-center select-none`} aria-hidden="true">
      {initials}
    </div>
  );
}
