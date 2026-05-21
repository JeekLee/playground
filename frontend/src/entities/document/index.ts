export type {
  Author,
  CreateDocumentRequest,
  DocSearchScope,
  Document,
  DocumentListItem,
  FolderListItem,
  MimeType,
  MyDocumentListItem,
  OwnerInfo,
  PatchDocumentRequest,
  SearchHit,
  Visibility,
} from './types';
export { authorInitials, displayInitials, formatDate, formatRelative } from './types';
export { isPdfSourced } from '@/shared/api/docs';
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
