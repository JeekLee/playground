export type {
  Author,
  CreateDocumentRequest,
  DocSearchScope,
  Document,
  DocumentListItem,
  ExtractionStatus,
  FolderListItem,
  MimeType,
  MyDocumentListItem,
  OwnerInfo,
  PatchDocumentRequest,
  SearchHit,
  Visibility,
} from './types';
export { authorInitials, displayInitials, formatDate, formatRelative } from './types';
export {
  hasOriginalBlob,
  isExtractionFailed,
  isExtractionInFlight,
  isExtractionTerminal,
  isPdfSourced,
} from '@/shared/api/docs';
export {
  buildFolderTree,
  flattenFolderTree,
  folderLabel,
  normalizeFolderPath,
  parentOf,
  ROOT_PATH,
  totalDocCount,
  type FolderTreeNode,
} from './folder-tree';
