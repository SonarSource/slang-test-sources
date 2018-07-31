import BufferedContent from "discourse/mixins/buffered-content";
import Composer from "discourse/models/composer";
import DiscourseURL from "discourse/lib/url";
import Post from "discourse/models/post";
import Quote from "discourse/lib/quote";
import QuoteState from "discourse/lib/quote-state";
import Topic from "discourse/models/topic";
import debounce from "discourse/lib/debounce";
import isElementInViewport from "discourse/lib/is-element-in-viewport";
import { ajax } from "discourse/lib/ajax";
import {
  default as computed,
  observes
} from "ember-addons/ember-computed-decorators";
import { extractLinkMeta } from "discourse/lib/render-topic-featured-link";
import { popupAjaxError } from "discourse/lib/ajax-error";
import { spinnerHTML } from "discourse/helpers/loading-spinner";
import { userPath } from "discourse/lib/url";
import showModal from "discourse/lib/show-modal";

let customPostMessageCallbacks = {};

export function resetCustomPostMessageCallbacks() {
  customPostMessageCallbacks = {};
}

export function registerCustomPostMessageCallback(type, callback) {
  if (customPostMessageCallbacks[type]) {
    throw new Error(`Error ${type} is an already registered post message!`);
  }

  customPostMessageCallbacks[type] = callback;
}

export default Ember.Controller.extend(BufferedContent, {
  composer: Ember.inject.controller(),
  application: Ember.inject.controller(),
  multiSelect: false,
  selectedPostIds: null,
  editingTopic: false,
  queryParams: ["filter", "username_filters"],
  loadedAllPosts: Ember.computed.or(
    "model.postStream.loadedAllPosts",
    "model.postStream.loadingLastPost"
  ),
  enteredAt: null,
  enteredIndex: null,
  retrying: false,
  userTriggeredProgress: null,
  _progressIndex: null,
  hasScrolled: null,
  username_filters: null,
  filter: null,
  quoteState: null,
  canRemoveTopicFeaturedLink: Ember.computed.and(
    "canEditTopicFeaturedLink",
    "buffered.featured_link"
  ),

  updateQueryParams() {
    this.setProperties(this.get("model.postStream.streamFilters"));
  },

  @observes("model.title", "category")
  _titleChanged() {
    const title = this.get("model.title");
    if (!Ember.isEmpty(title)) {
      // force update lazily loaded titles
      this.send("refreshTitle");
    }
  },

  @computed("model.postStream.loaded", "model.category_id")
  showSharedDraftControls(loaded, categoryId) {
    let draftCat = this.site.shared_drafts_category_id;
    return loaded && draftCat && categoryId && draftCat === categoryId;
  },

  @computed("site.mobileView", "model.posts_count")
  showSelectedPostsAtBottom(mobileView, postsCount) {
    return mobileView && postsCount > 3;
  },

  @computed("model.postStream.posts", "model.postStream.postsWithPlaceholders")
  postsToRender(posts, postsWithPlaceholders) {
    return this.capabilities.isAndroid ? posts : postsWithPlaceholders;
  },

  @computed("model.postStream.loadingFilter")
  androidLoading(loading) {
    return this.capabilities.isAndroid && loading;
  },

  @computed("model")
  pmPath(topic) {
    return this.currentUser && this.currentUser.pmPath(topic);
  },

  init() {
    this._super();
    this.setProperties({
      selectedPostIds: [],
      quoteState: new QuoteState()
    });
  },

  showCategoryChooser: Ember.computed.not("model.isPrivateMessage"),

  gotoInbox(name) {
    let url = userPath(this.get("currentUser.username_lower") + "/messages");
    if (name) {
      url = url + "/group/" + name;
    }
    DiscourseURL.routeTo(url);
  },

  @computed
  selectedQuery() {
    return post => this.postSelected(post);
  },

  @computed("model.isPrivateMessage", "model.category.id")
  canEditTopicFeaturedLink(isPrivateMessage, categoryId) {
    if (!this.siteSettings.topic_featured_link_enabled || isPrivateMessage) {
      return false;
    }

    const categoryIds = this.site.get(
      "topic_featured_link_allowed_category_ids"
    );
    return (
      categoryIds === undefined ||
      !categoryIds.length ||
      categoryIds.includes(categoryId)
    );
  },

  @computed("model")
  featuredLinkDomain(topic) {
    return extractLinkMeta(topic).domain;
  },

  @computed("model.isPrivateMessage")
  canEditTags(isPrivateMessage) {
    return (
      this.site.get("can_tag_topics") &&
      (!isPrivateMessage || this.site.get("can_tag_pms"))
    );
  },

  _forceRefreshPostStream() {
    this.appEvents.trigger("post-stream:refresh", { force: true });
  },

  _updateSelectedPostIds(postIds) {
    this.get("selectedPostIds").pushObjects(postIds);
    this.set("selectedPostIds", [...new Set(this.get("selectedPostIds"))]);
    this._forceRefreshPostStream();
  },

  _loadPostIds(post) {
    if (this.get("loadingPostIds")) return;

    const postStream = this.get("model.postStream");
    const url = `/t/${this.get("model.id")}/post_ids.json`;

    this.set("loadingPostIds", true);

    return ajax(url, {
      data: _.merge(
        { post_number: post.get("post_number") },
        postStream.get("streamFilters")
      )
    })
      .then(result => {
        result.post_ids.pushObject(post.get("id"));
        this._updateSelectedPostIds(result.post_ids);
      })
      .finally(() => {
        this.set("loadingPostIds", false);
      });
  },

  actions: {
    showPostFlags(post) {
      return this.send("showFlags", post);
    },

    topicRouteAction(name, model) {
      return this.send(name, model);
    },

    openFeatureTopic() {
      this.send("showFeatureTopic");
    },

    selectText(postId, buffer) {
      return this.get("model.postStream")
        .loadPost(postId)
        .then(post => {
          const composer = this.get("composer");
          const viewOpen = composer.get("model.viewOpen");
          const quotedText = Quote.build(post, buffer);

          // If we can't create a post, delegate to reply as new topic
          if (!viewOpen && !this.get("model.details.can_create_post")) {
            this.send("replyAsNewTopic", post, quotedText);
            return;
          }

          const composerOpts = {
            action: Composer.REPLY,
            draftKey: post.get("topic.draft_key")
          };

          if (post.get("post_number") === 1) {
            composerOpts.topic = post.get("topic");
          } else {
            composerOpts.post = post;
          }

          // If the composer is associated with a different post, we don't change it.
          const composerPost = composer.get("model.post");
          if (composerPost && composerPost.get("id") !== this.get("post.id")) {
            composerOpts.post = composerPost;
          }

          composerOpts.quote = quotedText;
          if (composer.get("model.viewOpen")) {
            this.appEvents.trigger("composer:insert-block", quotedText);
          } else if (composer.get("model.viewDraft")) {
            const model = composer.get("model");
            model.set("reply", model.get("reply") + quotedText);
            composer.send("openIfDraft");
          } else {
            composer.open(composerOpts);
          }
        });
    },

    fillGapBefore(args) {
      return this.get("model.postStream").fillGapBefore(args.post, args.gap);
    },

    fillGapAfter(args) {
      return this.get("model.postStream").fillGapAfter(args.post, args.gap);
    },

    currentPostChanged(event) {
      const { post } = event;
      if (!post) {
        return;
      }

      const postNumber = post.get("post_number");
      const topic = this.get("model");
      topic.set("currentPost", postNumber);
      if (postNumber > (topic.get("last_read_post_number") || 0)) {
        topic.set("last_read_post_id", post.get("id"));
        topic.set("last_read_post_number", postNumber);
      }

      this.send("postChangedRoute", postNumber);
      this._progressIndex = topic.get("postStream").progressIndexOfPost(post);

      this.appEvents.trigger("topic:current-post-changed", { post });
    },

    currentPostScrolled(event) {
      const total = this.get("model.postStream.filteredPostsCount");
      const percent =
        parseFloat(this._progressIndex + event.percent - 1) / total;
      this.appEvents.trigger("topic:current-post-scrolled", {
        postIndex: this._progressIndex,
        percent: Math.max(Math.min(percent, 1.0), 0.0)
      });
    },

    // Called when the topmost visible post on the page changes.
    topVisibleChanged(event) {
      const { post, refresh } = event;
      if (!post) {
        return;
      }

      const postStream = this.get("model.postStream");
      const firstLoadedPost = postStream.get("posts.firstObject");

      if (post.get("post_number") === 1) {
        return;
      }

      if (firstLoadedPost && firstLoadedPost === post) {
        postStream.prependMore().then(() => refresh());
      }
    },

    // Called the the bottommost visible post on the page changes.
    bottomVisibleChanged(event) {
      const { post, refresh } = event;

      const postStream = this.get("model.postStream");
      const lastLoadedPost = postStream.get("posts.lastObject");

      if (
        lastLoadedPost &&
        lastLoadedPost === post &&
        postStream.get("canAppendMore")
      ) {
        postStream.appendMore().then(() => refresh());
        // show loading stuff
        refresh();
      }
    },

    toggleSummary() {
      return this.get("model.postStream")
        .toggleSummary()
        .then(() => {
          this.updateQueryParams();
        });
    },

    removeAllowedUser(user) {
      return this.get("model.details")
        .removeAllowedUser(user)
        .then(() => {
          if (this.currentUser.id === user.id) {
            this.transitionToRoute("userPrivateMessages", user);
          }
        });
    },

    removeAllowedGroup(group) {
      return this.get("model.details").removeAllowedGroup(group);
    },

    deleteTopic() {
      this.deleteTopic();
    },

    // Archive a PM (as opposed to archiving a topic)
    toggleArchiveMessage() {
      const topic = this.get("model");

      if (topic.get("archiving")) {
        return;
      }

      const backToInbox = () => this.gotoInbox(topic.get("inboxGroupName"));

      if (topic.get("message_archived")) {
        topic.moveToInbox().then(backToInbox);
      } else {
        topic.archiveMessage().then(backToInbox);
      }
    },

    editFirstPost() {
      const postStream = this.get("model.postStream");
      let firstPost = postStream.get("posts.firstObject");

      if (firstPost.get("post_number") !== 1) {
        const postId = postStream.findPostIdForPostNumber(1);
        // try loading from identity map first
        firstPost = postStream.findLoadedPost(postId);
        if (firstPost === undefined) {
          return this.get("model.postStream")
            .loadPost(postId)
            .then(post => {
              this.send("editPost", post);
            });
        }
      }
      this.send("editPost", firstPost);
    },

    // Post related methods
    replyToPost(post) {
      const composerController = this.get("composer");
      const topic = post ? post.get("topic") : this.get("model");
      const quoteState = this.get("quoteState");
      const postStream = this.get("model.postStream");

      if (!postStream || !topic || !topic.get("details.can_create_post")) {
        return;
      }

      const quotedPost = postStream.findLoadedPost(quoteState.postId);
      const quotedText = Quote.build(quotedPost, quoteState.buffer);

      quoteState.clear();

      if (
        composerController.get("content.topic.id") === topic.get("id") &&
        composerController.get("content.action") === Composer.REPLY
      ) {
        composerController.set("content.post", post);
        composerController.set("content.composeState", Composer.OPEN);
        this.appEvents.trigger("composer:insert-block", quotedText.trim());
      } else {
        const opts = {
          action: Composer.REPLY,
          draftKey: topic.get("draft_key"),
          draftSequence: topic.get("draft_sequence")
        };

        if (quotedText) {
          opts.quote = quotedText;
        }

        if (post && post.get("post_number") !== 1) {
          opts.post = post;
        } else {
          opts.topic = topic;
        }

        composerController.open(opts);
      }
      return false;
    },

    recoverPost(post) {
      post.get("post_number") === 1 ? this.recoverTopic() : post.recover();
    },

    deletePost(post) {
      if (post.get("post_number") === 1) {
        return this.deleteTopic();
      } else if (!post.can_delete) {
        return false;
      }

      const user = this.currentUser;
      const refresh = () => this.appEvents.trigger("post-stream:refresh");
      const hasReplies = post.get("reply_count") > 0;
      const loadedPosts = this.get("model.postStream.posts");

      if (user.get("staff") && hasReplies) {
        ajax(`/posts/${post.id}/reply-ids.json`).then(replies => {
          const buttons = [];

          buttons.push({
            label: I18n.t("cancel"),
            class: "btn-danger right"
          });

          buttons.push({
            label: I18n.t("post.controls.delete_replies.just_the_post"),
            callback() {
              post
                .destroy(user)
                .then(refresh)
                .catch(error => {
                  popupAjaxError(error);
                  post.undoDeleteState();
                });
            }
          });

          if (replies.some(r => r.level > 1)) {
            buttons.push({
              label: I18n.t("post.controls.delete_replies.all_replies", {
                count: replies.length
              }),
              callback() {
                loadedPosts.forEach(
                  p =>
                    (p === post || replies.some(r => r.id === p.id)) &&
                    p.setDeletedState(user)
                );
                Post.deleteMany([post.id, ...replies.map(r => r.id)])
                  .then(refresh)
                  .catch(popupAjaxError);
              }
            });
          }

          const directReplyIds = replies
            .filter(r => r.level === 1)
            .map(r => r.id);

          buttons.push({
            label: I18n.t("post.controls.delete_replies.direct_replies", {
              count: directReplyIds.length
            }),
            class: "btn-primary",
            callback() {
              loadedPosts.forEach(
                p =>
                  (p === post || directReplyIds.includes(p.id)) &&
                  p.setDeletedState(user)
              );
              Post.deleteMany([post.id, ...directReplyIds])
                .then(refresh)
                .catch(popupAjaxError);
            }
          });

          bootbox.dialog(
            I18n.t("post.controls.delete_replies.confirm"),
            buttons
          );
        });
      } else {
        return post
          .destroy(user)
          .then(refresh)
          .catch(error => {
            popupAjaxError(error);
            post.undoDeleteState();
          });
      }
    },

    editPost(post) {
      if (!this.currentUser) {
        return bootbox.alert(I18n.t("post.controls.edit_anonymous"));
      } else if (!post.can_edit) {
        return false;
      }

      const composer = this.get("composer");
      let topic = this.get("model");
      const composerModel = composer.get("model");
      let editingFirst =
        composerModel &&
        (post.get("firstPost") || composerModel.get("editingFirstPost"));

      let editingSharedDraft = false;
      let draftsCategoryId = this.get("site.shared_drafts_category_id");
      if (draftsCategoryId && draftsCategoryId === topic.get("category.id")) {
        editingSharedDraft = post.get("firstPost");
      }

      const opts = {
        post,
        action: editingSharedDraft ? Composer.EDIT_SHARED_DRAFT : Composer.EDIT,
        draftKey: post.get("topic.draft_key"),
        draftSequence: post.get("topic.draft_sequence")
      };

      if (editingSharedDraft) {
        opts.destinationCategoryId = topic.get("destination_category_id");
      }

      // Cancel and reopen the composer for the first post
      if (editingFirst) {
        composer.cancelComposer().then(() => composer.open(opts));
      } else {
        composer.open(opts);
      }
    },

    toggleBookmark(post) {
      if (!this.currentUser) {
        return bootbox.alert(I18n.t("bookmarks.not_bookmarked"));
      } else if (post) {
        return post.toggleBookmark().catch(popupAjaxError);
      } else {
        return this.get("model")
          .toggleBookmark()
          .then(changedIds => {
            if (!changedIds) {
              return;
            }
            changedIds.forEach(id =>
              this.appEvents.trigger("post-stream:refresh", { id })
            );
          });
      }
    },

    jumpToIndex(index) {
      this._jumpToIndex(index);
    },

    jumpToDate(date) {
      this._jumpToDate(date);
    },

    jumpToPostPrompt() {
      const topic = this.get("model");
      const controller = showModal("jump-to-post", {
        modalClass: "jump-to-post-modal"
      });
      controller.setProperties({
        topic,
        postNumber: null,
        jumpToIndex: index => this.send("jumpToIndex", index),
        jumpToDate: date => this.send("jumpToDate", date)
      });
    },

    jumpToPost(postNumber) {
      if (this.get("model.postStream.isMegaTopic")) {
        this._jumpToPostNumber(postNumber);
      } else {
        const postStream = this.get("model.postStream");
        let postId = postStream.findPostIdForPostNumber(postNumber);

        // If we couldn't find the post, find the closest post to it
        if (!postId) {
          const closest = postStream.closestPostNumberFor(postNumber);
          postId = postStream.findPostIdForPostNumber(closest);
        }

        this._jumpToPostId(postId);
      }
    },

    jumpTop() {
      DiscourseURL.routeTo(this.get("model.firstPostUrl"), {
        skipIfOnScreen: false
      });
    },

    jumpBottom() {
      DiscourseURL.routeTo(this.get("model.lastPostUrl"), {
        skipIfOnScreen: false
      });
    },

    jumpUnread() {
      this._jumpToPostId(this.get("model.last_read_post_id"));
    },

    jumpToPostId(postId) {
      this._jumpToPostId(postId);
    },

    hideMultiSelect() {
      this.set("multiSelect", false);
      this._forceRefreshPostStream();
    },

    toggleMultiSelect() {
      this.toggleProperty("multiSelect");
      this._forceRefreshPostStream();
    },

    selectAll() {
      this.set("selectedPostIds", [...this.get("model.postStream.stream")]);
      this._forceRefreshPostStream();
    },

    deselectAll() {
      this.set("selectedPostIds", []);
      this._forceRefreshPostStream();
    },

    togglePostSelection(post) {
      const selected = this.get("selectedPostIds");
      selected.includes(post.id)
        ? selected.removeObject(post.id)
        : selected.addObject(post.id);
    },

    selectReplies(post) {
      ajax(`/posts/${post.id}/reply-ids.json`).then(replies => {
        const replyIds = replies.map(r => r.id);
        this.get("selectedPostIds").pushObjects([post.id, ...replyIds]);
        this._forceRefreshPostStream();
      });
    },

    selectBelow(post) {
      if (this.get("model.postStream.isMegaTopic")) {
        this._loadPostIds(post);
      } else {
        const stream = [...this.get("model.postStream.stream")];
        const below = stream.slice(stream.indexOf(post.id));
        this._updateSelectedPostIds(below);
      }
    },

    deleteSelected() {
      const user = this.currentUser;

      bootbox.confirm(
        I18n.t("post.delete.confirm", {
          count: this.get("selectedPostsCount")
        }),
        result => {
          if (result) {
            // If all posts are selected, it's the same thing as deleting the topic
            if (this.get("selectedAllPosts")) return this.deleteTopic();

            Post.deleteMany(this.get("selectedPostIds"));
            this.get("model.postStream.posts").forEach(
              p => this.postSelected(p) && p.setDeletedState(user)
            );
            this.send("toggleMultiSelect");
          }
        }
      );
    },

    mergePosts() {
      bootbox.confirm(
        I18n.t("post.merge.confirm", { count: this.get("selectedPostsCount") }),
        result => {
          if (result) {
            Post.mergePosts(this.get("selectedPostIds"));
            this.send("toggleMultiSelect");
          }
        }
      );
    },

    changePostOwner(post) {
      this.set("selectedPostIds", [post.id]);
      this.send("changeOwner");
    },

    lockPost(post) {
      return post.updatePostField("locked", true);
    },

    unlockPost(post) {
      return post.updatePostField("locked", false);
    },

    grantBadge(post) {
      this.set("selectedPostIds", [post.id]);
      this.send("showGrantBadgeModal");
    },

    toggleParticipant(user) {
      this.get("model.postStream")
        .toggleParticipant(user.get("username"))
        .then(() => this.updateQueryParams);
    },

    editTopic() {
      if (this.get("model.details.can_edit")) {
        this.set("editingTopic", true);
      }
      return false;
    },

    cancelEditingTopic() {
      this.set("editingTopic", false);
      this.rollbackBuffer();
    },

    finishedEditingTopic() {
      if (!this.get("editingTopic")) {
        return;
      }

      // save the modifications
      const props = this.get("buffered.buffer");

      Topic.update(this.get("model"), props)
        .then(() => {
          // We roll back on success here because `update` saves the properties to the topic
          this.rollbackBuffer();
          this.set("editingTopic", false);
        })
        .catch(popupAjaxError);
    },

    expandHidden(post) {
      return post.expandHidden();
    },

    toggleVisibility() {
      this.get("content").toggleStatus("visible");
    },

    toggleClosed() {
      const topic = this.get("content");

      this.get("content")
        .toggleStatus("closed")
        .then(result => {
          topic.set("topic_status_update", result.topic_status_update);
        });
    },

    recoverTopic() {
      this.get("content").recover();
    },

    makeBanner() {
      this.get("content").makeBanner();
    },

    removeBanner() {
      this.get("content").removeBanner();
    },

    togglePinned() {
      const value = this.get("model.pinned_at") ? false : true,
        topic = this.get("content"),
        until = this.get("model.pinnedInCategoryUntil");

      // optimistic update
      topic.setProperties({
        pinned_at: value ? moment() : null,
        pinned_globally: false,
        pinned_until: value ? until : null
      });

      return topic.saveStatus("pinned", value, until);
    },

    pinGlobally() {
      const topic = this.get("content"),
        until = this.get("model.pinnedGloballyUntil");

      // optimistic update
      topic.setProperties({
        pinned_at: moment(),
        pinned_globally: true,
        pinned_until: until
      });

      return topic.saveStatus("pinned_globally", true, until);
    },

    toggleArchived() {
      this.get("content").toggleStatus("archived");
    },

    clearPin() {
      this.get("content").clearPin();
    },

    togglePinnedForUser() {
      if (this.get("model.pinned_at")) {
        const topic = this.get("content");
        if (topic.get("pinned")) {
          topic.clearPin();
        } else {
          topic.rePin();
        }
      }
    },

    replyAsNewTopic(post, quotedText) {
      const composerController = this.get("composer");

      const { quoteState } = this;
      quotedText = quotedText || Quote.build(post, quoteState.buffer);
      quoteState.clear();

      var options;
      if (this.get("model.isPrivateMessage")) {
        let users = this.get("model.details.allowed_users");
        let groups = this.get("model.details.allowed_groups");

        let usernames = [];
        users.forEach(user => usernames.push(user.username));
        groups.forEach(group => usernames.push(group.name));
        usernames = usernames.join();

        options = {
          action: Composer.PRIVATE_MESSAGE,
          archetypeId: "private_message",
          draftKey: Composer.REPLY_AS_NEW_PRIVATE_MESSAGE_KEY,
          usernames: usernames
        };
      } else {
        options = {
          action: Composer.CREATE_TOPIC,
          draftKey: Composer.REPLY_AS_NEW_TOPIC_KEY,
          categoryId: this.get("model.category.id")
        };
      }

      composerController
        .open(options)
        .then(() => {
          return Em.isEmpty(quotedText) ? "" : quotedText;
        })
        .then(q => {
          const postUrl = `${location.protocol}//${location.host}${post.get(
            "url"
          )}`;
          const postLink = `[${Handlebars.escapeExpression(
            this.get("model.title")
          )}](${postUrl})`;
          composerController
            .get("model")
            .prependText(
              `${I18n.t("post.continue_discussion", { postLink })}\n\n${q}`,
              { new_line: true }
            );
        });
    },

    retryLoading() {
      this.set("retrying", true);
      const rollback = () => this.set("retrying", false);
      this.get("model.postStream")
        .refresh()
        .then(rollback, rollback);
    },

    toggleWiki(post) {
      return post.updatePostField("wiki", !post.get("wiki"));
    },

    togglePostType(post) {
      const regular = this.site.get("post_types.regular");
      const moderator = this.site.get("post_types.moderator_action");
      return post.updatePostField(
        "post_type",
        post.get("post_type") === moderator ? regular : moderator
      );
    },

    rebakePost(post) {
      return post.rebake();
    },

    unhidePost(post) {
      return post.unhide();
    },

    convertToPublicTopic() {
      this.get("content").convertTopic("public");
    },

    convertToPrivateMessage() {
      this.get("content").convertTopic("private");
    },

    removeFeaturedLink() {
      this.set("buffered.featured_link", null);
    }
  },

  _jumpToIndex(index) {
    const postStream = this.get("model.postStream");

    if (postStream.get("isMegaTopic")) {
      this._jumpToPostNumber(index);
    } else {
      const stream = postStream.get("stream");
      const streamIndex = Math.max(1, Math.min(stream.length, index));
      this._jumpToPostId(stream[streamIndex - 1]);
    }
  },

  _jumpToDate(date) {
    const postStream = this.get("model.postStream");

    postStream
      .loadNearestPostToDate(date)
      .then(post => {
        DiscourseURL.routeTo(
          this.get("model").urlForPostNumber(post.get("post_number"))
        );
      })
      .catch(() => {
        this._jumpToIndex(postStream.get("topic.highest_post_number"));
      });
  },

  _jumpToPostNumber(postNumber) {
    const postStream = this.get("model.postStream");
    const post = postStream.get("posts").findBy("post_number", postNumber);

    if (post) {
      DiscourseURL.routeTo(
        this.get("model").urlForPostNumber(post.get("post_number"))
      );
    } else {
      postStream.loadPostByPostNumber(postNumber).then(p => {
        DiscourseURL.routeTo(
          this.get("model").urlForPostNumber(p.get("post_number"))
        );
      });
    }
  },

  _jumpToPostId(postId) {
    if (!postId) {
      Ember.Logger.warn(
        "jump-post code broken - requested an index outside the stream array"
      );
      return;
    }

    this.appEvents.trigger("topic:jump-to-post", postId);

    const topic = this.get("model");
    const postStream = topic.get("postStream");
    const post = postStream.findLoadedPost(postId);

    if (post) {
      DiscourseURL.routeTo(topic.urlForPostNumber(post.get("post_number")));
    } else {
      // need to load it
      postStream.findPostsByIds([postId]).then(arr => {
        DiscourseURL.routeTo(topic.urlForPostNumber(arr[0].get("post_number")));
      });
    }
  },

  togglePinnedState() {
    this.send("togglePinnedForUser");
  },

  print() {
    if (this.siteSettings.max_prints_per_hour_per_user > 0) {
      window.open(
        this.get("model.printUrl"),
        "",
        "menubar=no,toolbar=no,resizable=yes,scrollbars=yes,width=600,height=315"
      );
    }
  },

  hasError: Ember.computed.or("model.notFoundHtml", "model.message"),
  noErrorYet: Ember.computed.not("hasError"),

  categories: Ember.computed.alias("site.categoriesList"),

  selectedPostsCount: Ember.computed.alias("selectedPostIds.length"),

  @computed(
    "selectedPostIds",
    "model.postStream.posts",
    "selectedPostIds.[]",
    "model.postStream.posts.[]"
  )
  selectedPosts(selectedPostIds, loadedPosts) {
    return selectedPostIds
      .map(id => loadedPosts.find(p => p.id === id))
      .filter(post => post !== undefined);
  },

  @computed("selectedPostsCount", "selectedPosts", "selectedPosts.[]")
  selectedPostsUsername(selectedPostsCount, selectedPosts) {
    if (selectedPosts.length < 1 || selectedPostsCount > selectedPosts.length) {
      return undefined;
    }
    const username = selectedPosts[0].username;
    return selectedPosts.every(p => p.username === username)
      ? username
      : undefined;
  },

  @computed(
    "selectedPostsCount",
    "model.postStream.isMegaTopic",
    "model.postStream.stream.length",
    "model.posts_count"
  )
  selectedAllPosts(
    selectedPostsCount,
    isMegaTopic,
    postsCount,
    topicPostsCount
  ) {
    if (isMegaTopic) {
      return selectedPostsCount >= topicPostsCount;
    } else {
      return selectedPostsCount >= postsCount;
    }
  },

  @computed("selectedAllPosts", "model.postStream.isMegaTopic")
  canSelectAll(selectedAllPosts, isMegaTopic) {
    return isMegaTopic ? false : !selectedAllPosts;
  },

  canDeselectAll: Ember.computed.alias("selectedAllPosts"),

  @computed(
    "currentUser.staff",
    "selectedPostsCount",
    "selectedAllPosts",
    "selectedPosts",
    "selectedPosts.[]"
  )
  canDeleteSelected(
    isStaff,
    selectedPostsCount,
    selectedAllPosts,
    selectedPosts
  ) {
    return (
      selectedPostsCount > 0 &&
      ((selectedAllPosts && isStaff) || selectedPosts.every(p => p.can_delete))
    );
  },

  @computed(
    "canMergeTopic",
    "selectedAllPosts",
    "selectedPosts",
    "selectedPosts.[]"
  )
  canSplitTopic(canMergeTopic, selectedAllPosts, selectedPosts) {
    return (
      canMergeTopic &&
      !selectedAllPosts &&
      selectedPosts.length > 0 &&
      selectedPosts.sort((a, b) => a.post_number - b.post_number)[0]
        .post_type === 1
    );
  },

  @computed("model.details.can_move_posts", "selectedPostsCount")
  canMergeTopic(canMovePosts, selectedPostsCount) {
    return canMovePosts && selectedPostsCount > 0;
  },

  @computed("currentUser.admin", "selectedPostsCount", "selectedPostsUsername")
  canChangeOwner(isAdmin, selectedPostsCount, selectedPostsUsername) {
    return (
      isAdmin && selectedPostsCount > 0 && selectedPostsUsername !== undefined
    );
  },

  @computed(
    "selectedPostsCount",
    "selectedPostsUsername",
    "selectedPosts",
    "selectedPosts.[]"
  )
  canMergePosts(selectedPostsCount, selectedPostsUsername, selectedPosts) {
    return (
      selectedPostsCount > 1 &&
      selectedPostsUsername !== undefined &&
      selectedPosts.every(p => p.can_delete)
    );
  },

  @observes("multiSelect")
  _multiSelectChanged() {
    this.set("selectedPostIds", []);
  },

  postSelected(post) {
    return (
      this.get("selectedAllPost") ||
      this.get("selectedPostIds").includes(post.id)
    );
  },

  @computed
  loadingHTML() {
    return spinnerHTML;
  },

  recoverTopic() {
    this.get("content").recover();
  },

  deleteTopic() {
    this.get("content").destroy(this.currentUser);
  },

  subscribe() {
    this.unsubscribe();

    const refresh = args => this.appEvents.trigger("post-stream:refresh", args);

    this.messageBus.subscribe(
      `/topic/${this.get("model.id")}`,
      data => {
        const topic = this.get("model");

        if (Ember.isPresent(data.notification_level_change)) {
          topic.set(
            "details.notification_level",
            data.notification_level_change
          );
          topic.set(
            "details.notifications_reason_id",
            data.notifications_reason_id
          );
          return;
        }

        const postStream = this.get("model.postStream");

        if (data.reload_topic) {
          topic.reload().then(() => {
            this.send("postChangedRoute", topic.get("post_number") || 1);
            this.appEvents.trigger("header:update-topic", topic);
            if (data.refresh_stream) postStream.refresh();
          });

          return;
        }

        switch (data.type) {
          case "acted":
            postStream
              .triggerChangedPost(data.id, data.updated_at, {
                preserveCooked: true
              })
              .then(() => refresh({ id: data.id, refreshLikes: true }));
            break;
          case "revised":
          case "rebaked": {
            postStream
              .triggerChangedPost(data.id, data.updated_at)
              .then(() => refresh({ id: data.id }));
            break;
          }
          case "deleted": {
            postStream
              .triggerDeletedPost(data.id)
              .then(() => refresh({ id: data.id }));
            break;
          }
          case "recovered": {
            postStream
              .triggerRecoveredPost(data.id)
              .then(() => refresh({ id: data.id }));
            break;
          }
          case "created": {
            postStream.triggerNewPostInStream(data.id).then(() => refresh());
            if (this.get("currentUser.id") !== data.user_id) {
              Discourse.notifyBackgroundCountIncrement();
            }
            break;
          }
          case "move_to_inbox": {
            topic.set("message_archived", false);
            break;
          }
          case "archived": {
            topic.set("message_archived", true);
            break;
          }
          default: {
            let callback = customPostMessageCallbacks[data.type];
            if (callback) {
              callback(this, data);
            } else {
              Em.Logger.warn("unknown topic bus message type", data);
            }
          }
        }

        if (
          topic.get("isPrivateMessage") &&
          this.currentUser &&
          this.currentUser.get("id") !== data.user_id &&
          data.type === "created"
        ) {
          const postNumber = data.post_number;
          const notInPostStream =
            topic.get("highest_post_number") <= postNumber;
          const postNumberDifference = postNumber - topic.get("currentPost");

          if (
            notInPostStream &&
            postNumberDifference > 0 &&
            postNumberDifference < 7
          ) {
            this._scrollToPost(data.post_number);
          }
        }
      },
      this.get("model.message_bus_last_id")
    );
  },

  _scrollToPost: debounce(function(postNumber) {
    const $post = $(`.topic-post article#post_${postNumber}`);

    if ($post.length === 0 || isElementInViewport($post)) return;

    $("body").animate({ scrollTop: $post.offset().top }, 1000);
  }, 500),

  unsubscribe() {
    // never unsubscribe when navigating from topic to topic
    if (!this.get("content.id")) return;
    this.messageBus.unsubscribe("/topic/*");
  },

  reply() {
    this.replyToPost();
  },

  readPosts(topicId, postNumbers) {
    const topic = this.get("model");
    const postStream = topic.get("postStream");

    if (topic.get("id") === topicId) {
      postStream.get("posts").forEach(post => {
        if (!post.read && postNumbers.includes(post.post_number)) {
          post.set("read", true);
          this.appEvents.trigger("post-stream:refresh", { id: post.get("id") });
        }
      });

      if (
        this.siteSettings.automatically_unpin_topics &&
        this.currentUser &&
        this.currentUser.automatically_unpin_topics
      ) {
        // automatically unpin topics when the user reaches the bottom
        const max = _.max(postNumbers);
        if (topic.get("pinned") && max >= topic.get("highest_post_number")) {
          Ember.run.next(() => topic.clearPin());
        }
      }
    }
  },

  @observes("model.postStream.loaded", "model.postStream.loadedAllPosts")
  _showFooter() {
    const showFooter =
      this.get("model.postStream.loaded") &&
      this.get("model.postStream.loadedAllPosts");
    this.set("application.showFooter", showFooter);
  }
});
