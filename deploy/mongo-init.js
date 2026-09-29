// Runs once, on the first start of an empty /data/db volume.
// Creates the least-privileged application user. The API never connects as root.
const database = process.env.MONGO_INITDB_DATABASE || 'evangelizae';
const user = process.env.MONGO_APP_USERNAME;
const password = process.env.MONGO_APP_PASSWORD;

if (!user || !password) {
  throw new Error('MONGO_APP_USERNAME and MONGO_APP_PASSWORD must be set before first start');
}

const appDatabase = db.getSiblingDB(database);

if (!appDatabase.getUser(user)) {
  appDatabase.createUser({
    user: user,
    pwd: password,
    roles: [{ role: 'readWrite', db: database }],
  });
  print(`created readWrite user "${user}" on database "${database}"`);
}
