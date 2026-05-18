export type {
  Author,
  CreateDocumentRequest,
  DocSearchScope,
  Document,
  DocumentListItem,
  FolderListItem,
  MyDocumentListItem,
  OwnerInfo,
  PatchDocumentRequest,
  SearchHit,
  Visibility,
} from './types';
export { authorInitials, displayInitials, formatDate, formatRelative } from './types';
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
