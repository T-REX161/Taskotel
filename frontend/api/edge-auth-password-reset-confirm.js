import { createEdgeHandler } from '../server/edgeProxy.js';

// POST /api/auth/password-reset/confirm is served by this function (see vercel.json); the backend path is fixed here, never taken from the request.
export default createEdgeHandler({ method: 'POST', path: '/api/auth/password-reset/confirm' });
