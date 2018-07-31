import DiscourseURL from "discourse/lib/url";
import Draft from "discourse/models/draft";

// This route is used for retrieving a topic based on params
export default Discourse.Route.extend({
  // Avoid default model hook
  model(params) {
    return params;
  },

  deactivate() {
    this._super();
    this.controllerFor("topic").unsubscribe();
  },

  setupController(controller, params) {
    params = params || {};
    params.track_visit = true;

    const self = this,
      topic = this.modelFor("topic"),
      postStream = topic.get("postStream"),
      topicController = this.controllerFor("topic"),
      composerController = this.controllerFor("composer");

    // I sincerely hope no topic gets this many posts
    if (params.nearPost === "last") {
      params.nearPost = 999999999;
    }

    params.forceLoad = true;

    postStream
      .refresh(params)
      .then(function() {
        // TODO we are seeing errors where closest post is null and this is exploding
        // we need better handling and logging for this condition.

        // The post we requested might not exist. Let's find the closest post
        const closestPost = postStream.closestPostForPostNumber(
          params.nearPost || 1
        );
        const closest = closestPost.get("post_number");

        topicController.setProperties({
          "model.currentPost": closest,
          enteredIndex: topic
            .get("postStream")
            .progressIndexOfPost(closestPost),
          enteredAt: new Date().getTime().toString()
        });

        topicController.subscribe();

        // Highlight our post after the next render
        Ember.run.scheduleOnce("afterRender", function() {
          self.appEvents.trigger("post:highlight", closest);
        });

        const opts = {};
        if (document.location.hash && document.location.hash.length) {
          opts.anchor = document.location.hash;
        }
        DiscourseURL.jumpToPost(closest, opts);

        if (!Ember.isEmpty(topic.get("draft"))) {
          composerController.open({
            draft: Draft.getLocal(topic.get("draft_key"), topic.get("draft")),
            draftKey: topic.get("draft_key"),
            draftSequence: topic.get("draft_sequence"),
            topic: topic,
            ignoreIfChanged: true
          });
        }
      })
      .catch(e => {
        if (!Ember.testing) {
          console.log("Could not view topic", e);
        }
      });
  }
});
