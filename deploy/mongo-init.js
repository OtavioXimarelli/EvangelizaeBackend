// Runs during first-time Mongo initialization and from the mongo-setup service.
// Keeps the least-privileged API user in sync; the API never connects as root.
const database = process.env.MONGO_INITDB_DATABASE || 'evangelizae';
const user = process.env.MONGO_APP_USERNAME;
const password = process.env.MONGO_APP_PASSWORD;

if (!user || !password) {
  throw new Error('MONGO_APP_USERNAME and MONGO_APP_PASSWORD must be set before first start');
}

const appDatabase = db.getSiblingDB(database);

const roles = [{ role: 'readWrite', db: database }];
if (appDatabase.getUser(user)) {
  appDatabase.updateUser(user, { pwd: password, roles });
  print(`updated readWrite user "${user}" on database "${database}"`);
} else {
  appDatabase.createUser({
    user: user,
    pwd: password,
    roles,
  });
  print(`created readWrite user "${user}" on database "${database}"`);
}
