'use client';

import dynamic from 'next/dynamic';

export const DynamicCanvas = dynamic(
  () =>
    import('./AnnotationCanvas').then(
      mod => mod.AnnotationCanvas
    ),
  {
    ssr: false,
  }
);