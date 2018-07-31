import PreloadStore from "preload-store";

export default Ember.Route.extend({
  // since the logs are pushed via the message bus
  // we only want to preload them (hence the beforeModel hook)
  beforeModel() {
    const logs = this.controllerFor("adminBackupsLogs").get("logs");
    // preload the logs if any
    PreloadStore.getAndRemove("logs").then(function(preloadedLogs) {
      if (preloadedLogs && preloadedLogs.length) {
        // we need to filter out message like: "[SUCCESS]"
        // and convert POJOs to Ember Objects
        const newLogs = _.chain(preloadedLogs)
          .reject(function(log) {
            return log.message.length === 0 || log.message[0] === "[";
          })
          .map(function(log) {
            return Em.Object.create(log);
          })
          .value();
        logs.pushObjects(newLogs);
      }
    });
  },

  setupController() {
    /* prevent default behavior */
  }
});
