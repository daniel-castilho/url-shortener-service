// Application DB user for the URL Shortener Service.
// Runs once, on first boot of the mongo container (docker-entrypoint-initdb.d),
// after the root user has been created. Values come from the container env (.env).
const appUser = process.env.MONGO_APP_USER;
const appPassword = process.env.MONGO_APP_PASSWORD;
const appDb = process.env.MONGO_APP_DB || "url_shortener";

if (!appUser || !appPassword) {
  throw new Error("MONGO_APP_USER / MONGO_APP_PASSWORD must be set");
}

db = db.getSiblingDB(appDb);
db.createUser({
  user: appUser,
  pwd: appPassword,
  roles: [{ role: "readWrite", db: appDb }],
});
print("created readWrite user '" + appUser + "' on db '" + appDb + "'");
