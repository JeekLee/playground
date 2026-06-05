/**
 * JSX typing for the <model-viewer> web component (@google/model-viewer).
 * The element is registered lazily — PreviewAccordion dynamic-imports the
 * package on first open — so this file only teaches TSX the tag name and
 * the attributes we actually use. React 18 passes unknown props on custom
 * elements through as HTML attributes.
 */
import type { DetailedHTMLProps, HTMLAttributes } from 'react';

declare global {
  namespace JSX {
    interface IntrinsicElements {
      'model-viewer': DetailedHTMLProps<HTMLAttributes<HTMLElement>, HTMLElement> & {
        src?: string;
        'camera-controls'?: boolean;
        'auto-rotate'?: boolean;
        'shadow-intensity'?: string;
      };
    }
  }
}
