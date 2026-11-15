// Minimal in-memory stand-in for the Web Cache Storage API, used by tests.
export function createMemoryCache() {
  const stores = new Map();
  return {
    async open(name) {
      if (!stores.has(name)) stores.set(name, new Map());
      const store = stores.get(name);
      return {
        async match(request) {
          const key = typeof request === 'string' ? request : request.url;
          return store.get(key) || undefined;
        },
        async put(request, response) {
          const key = typeof request === 'string' ? request : request.url;
          store.set(key, response);
        },
        async delete(request) {
          const key = typeof request === 'string' ? request : request.url;
          return store.delete(key);
        }
      };
    },
    async delete(name) {
      return stores.delete(name);
    },
    async keys() {
      return [...stores.keys()];
    }
  };
}
