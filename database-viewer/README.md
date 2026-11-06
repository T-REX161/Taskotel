# database-viewer

Prisma Studio for **looking at** the Taskotel PostgreSQL database. It is an inspection tool only.

## Rules

- Flyway migrations (`backend/src/main/resources/db/migration`) own the database schema. Hibernate only validates it in production.
- **Allowed:** `npx prisma studio`, and `npx prisma db pull` (reads the database and refreshes `prisma/schema.prisma`).
- **Never run against the Taskotel database:** `prisma db push`, `prisma migrate dev|deploy|reset`, or any Prisma migration.
  They change or reset the schema behind Flyway's back. `prisma/schema.prisma` here is a snapshot, not the source of truth.
- Studio can edit rows. Treat it as read-only on real data, or connect with a read-only database user.

## Setup

1. `npm install`
2. Copy `.env.example` to `.env` and set `DATABASE_URL` (`.env` is git-ignored).
3. `npx prisma studio`
