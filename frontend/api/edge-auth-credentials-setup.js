import { createEdgeHandler } from '../server/edgeProxy.js';

// PUT /api/auth/credentials/setup is served by this function (see vercel.json); the backend path is fixed here, never taken from the request.
export default createEdgeHandler({ method: 'PUT', path: '/api/auth/credentials/setup' });
