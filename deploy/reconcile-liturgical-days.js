// Pre-deploy gate for the strict provider read path.
//
// The API refuses to serve a document whose `provider` is unknown: it returns
// 503 rather than labelling the source "mongodb". That is correct, and it means
// this script must pass before the deployment that introduces the strict path.
// Ship the code against an unreconciled collection and /pt/liturgy goes dark.
//
// Usage, from the repository root on the VPS:
//
//   # gate: prints the report and exits non-zero if anything is unreconciled
//   docker compose exec -T -e RECONCILE_MODE=check mongo mongosh \
//     -u "$MONGO_APP_USERNAME" -p "$MONGO_APP_PASSWORD" \
//     --authenticationDatabase "$MONGO_DATABASE" --quiet \
//     deploy/reconcile-liturgical-days.js
//
//   # repair: backfill provider from the PRIMARY source already stored on the
//   # document. Never invents a value - a document with no PRIMARY source is
//   # reported and left alone, because its provenance is genuinely unknown.
//   docker compose exec -T -e RECONCILE_MODE=fix mongo mongosh \
//     -u "$MONGO_APP_USERNAME" -p "$MONGO_APP_PASSWORD" \
//     --authenticationDatabase "$MONGO_DATABASE" --quiet \
//     deploy/reconcile-liturgical-days.js
//
// The mode is an environment variable, not an argument: mongosh rejects trailing
// command-line arguments after the script path. Both modes are idempotent.
// `--fix` on an already reconciled collection changes nothing and reports zero
// repairs. `fix` runs the gate afterwards, so a repair that did not fully
// succeed still fails the deploy.

const database = process.env.MONGO_DATABASE || 'evangelizae';
const collection = db.getSiblingDB(database).getCollection('liturgical_days');
const mode = process.env.RECONCILE_MODE === 'fix' ? 'fix' : 'check';

const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/;
const EXPECTED_PROVIDER = 'CNBB';

// A document is reconciled when its _id is the ISO date the API looks it up by
// and its provider names the source the readings actually came from.
const documents = collection.find({}).toArray();

const notDateKeyed = documents.filter((doc) => !ISO_DATE.test(String(doc._id)));
const providerMismatched = documents.filter(
  (doc) => doc.provider !== EXPECTED_PROVIDER
);
const missingIdDate = documents.filter((doc) => !doc.date);

print(`collection: ${collection.getName()}`);
print(`documents:  ${documents.length}`);

if (documents.length === 0) {
  print('collection is empty: backfill the window before deploying');
  quit(2);
}

if (notDateKeyed.length > 0) {
  print(`\nnot date-keyed _id: ${notDateKeyed.length}`);
  notDateKeyed.slice(0, 20).forEach((doc) => print(`  _id=${doc._id}`));
  if (notDateKeyed.length > 20) {
    print(`  ... and ${notDateKeyed.length - 20} more`);
  }
  print('  the API looks a day up by findByDate(date); these rows are unreachable');
}

if (missingIdDate.length > 0) {
  print(`\nmissing date field: ${missingIdDate.length}`);
  missingIdDate.slice(0, 20).forEach((doc) => print(`  _id=${doc._id}`));
}

if (providerMismatched.length > 0) {
  print(`\nprovider not "${EXPECTED_PROVIDER}": ${providerMismatched.length}`);
  providerMismatched.slice(0, 20).forEach((doc) => {
    const primary = (doc.sources || []).find((source) => source.role === 'PRIMARY');
    print(`  _id=${doc._id} provider=${JSON.stringify(doc.provider)} primarySource=${JSON.stringify(primary && primary.name)}`);
  });
  if (providerMismatched.length > 20) {
    print(`  ... and ${providerMismatched.length - 20} more`);
  }
}

if (mode === 'fix') {
  let repaired = 0;
  let unrecoverable = 0;
  collection.find({ provider: { $ne: EXPECTED_PROVIDER } }).forEach((doc) => {
    const primary = (doc.sources || []).find((source) => source.role === 'PRIMARY');
    if (!primary || !primary.name) {
      unrecoverable += 1;
      print(`  UNRECOVERABLE _id=${doc._id}: no PRIMARY source; re-import this date`);
      return;
    }
    collection.updateOne({ _id: doc._id }, { $set: { provider: primary.name } });
    repaired += 1;
  });
  print(`\nrepaired ${repaired} document(s), ${unrecoverable} unrecoverable`);
  // Fall through to the gate below so a repair that did not fully succeed still
  // fails the deploy.
}

const stillWrong = collection.countDocuments({ provider: { $ne: EXPECTED_PROVIDER } });
const stillNotDateKeyed = documents.filter((doc) => !ISO_DATE.test(String(doc._id))).length;

if (stillWrong > 0 || stillNotDateKeyed > 0 || missingIdDate.length > 0) {
  print('\nUNRECONCILED - do not deploy the strict provider read path yet');
  quit(1);
}

print('\nreconciled - safe to deploy');
