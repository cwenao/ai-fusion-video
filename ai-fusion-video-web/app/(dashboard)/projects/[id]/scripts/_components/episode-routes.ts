export function episodeEditorHref(projectId: number, episodeId: number) {
  return `/projects/${projectId}/scripts/episodes/${episodeId}/edit`;
}

export function episodeWorkspaceHref(projectId: number, episodeId: number) {
  return `/projects/${projectId}/scripts?episode=${episodeId}`;
}
