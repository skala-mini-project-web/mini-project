// Holds the current demo identity so the api layer can attach the
// X-Demo-User-Id / X-Demo-Role headers without importing the Pinia store
// (avoids a circular dependency). The session store keeps this in sync.
let current = null
export function setAuth(auth) {
  current = auth
}
export function getAuth() {
  return current
}
