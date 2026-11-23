import { createEdgeHandler } from '../server/edgeProxy.js';

// POST /api/auth/email-verification/send is served by this function (see vercel.json); the backend path is fixed here, never taken from the request.
export default createEdgeHandler({ method: 'POST', path: '/api/auth/email-verification/send' });
