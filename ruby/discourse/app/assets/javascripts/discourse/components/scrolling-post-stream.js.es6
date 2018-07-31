import DiscourseURL from "discourse/lib/url";
import MountWidget from "discourse/components/mount-widget";
import { cloak, uncloak } from "discourse/widgets/post-stream";
import { isWorkaroundActive } from "discourse/lib/safari-hacks";
import offsetCalculator from "discourse/lib/offset-calculator";
import optionalService from "discourse/lib/optional-service";

function findTopView($posts, viewportTop, postsWrapperTop, min, max) {
  if (max < min) {
    return min;
  }

  while (max > min) {
    const mid = Math.floor((min + max) / 2);
    const $post = $($posts[mid]);
    const viewBottom = $post.offset().top - postsWrapperTop + $post.height();

    if (viewBottom > viewportTop) {
      max = mid - 1;
    } else {
      min = mid + 1;
    }
  }

  return min;
}

export default MountWidget.extend({
  adminTools: optionalService(),
  widget: "post-stream",
  _topVisible: null,
  _bottomVisible: null,
  _currentPost: null,
  _currentVisible: null,
  _currentPercent: null,

  buildArgs() {
    return this.getProperties(
      "posts",
      "canCreatePost",
      "multiSelect",
      "gaps",
      "selectedQuery",
      "selectedPostsCount",
      "searchService"
    );
  },

  beforePatch() {
    const $body = $(document);
    this.prevHeight = $body.height();
    this.prevScrollTop = $body.scrollTop();
  },

  afterPatch() {
    const $body = $(document);
    const height = $body.height();
    const scrollTop = $body.scrollTop();

    // This hack is for when swapping out many cloaked views at once
    // when using keyboard navigation. It could suddenly move the scroll
    if (this.prevHeight === height && scrollTop !== this.prevScrollTop) {
      $body.scrollTop(this.prevScrollTop);
    }
  },

  scrolled() {
    if (this.isDestroyed || this.isDestroying) {
      return;
    }
    if (isWorkaroundActive()) {
      return;
    }

    // We use this because watching videos fullscreen in Chrome was super buggy
    // otherwise. Thanks to arrendek from q23 for the technique.
    if (document.elementFromPoint(0, 0).tagName.toUpperCase() === "IFRAME") {
      return;
    }

    const $w = $(window);
    const windowHeight = window.innerHeight ? window.innerHeight : $w.height();
    const slack = Math.round(windowHeight * 5);
    const onscreen = [];
    const nearby = [];

    const windowTop = $w.scrollTop();

    const postsWrapperTop = $(".posts-wrapper").offset().top;
    const $posts = this.$(".onscreen-post, .cloaked-post");
    const viewportTop = windowTop - slack;
    const topView = findTopView(
      $posts,
      viewportTop,
      postsWrapperTop,
      0,
      $posts.length - 1
    );

    let windowBottom = windowTop + windowHeight;
    let viewportBottom = windowBottom + slack;

    const bodyHeight = $("body").height();
    if (windowBottom > bodyHeight) {
      windowBottom = bodyHeight;
    }
    if (viewportBottom > bodyHeight) {
      viewportBottom = bodyHeight;
    }

    let currentPost = null;
    let percent = null;

    const offset = offsetCalculator();
    const topCheck = Math.ceil(windowTop + offset + 5);

    // uncomment to debug the eyeline
    /*
    let $eyeline = $('.debug-eyeline');
    if ($eyeline.length === 0) {
      $('body').prepend('<div class="debug-eyeline"></div>');
      $eyeline = $('.debug-eyeline');
    }
    $eyeline.css({ height: '5px', width: '100%', backgroundColor: 'blue', position: 'absolute', top: `${topCheck}px`, zIndex: 999999 });
    */

    let allAbove = true;
    let bottomView = topView;
    let lastBottom = 0;
    while (bottomView < $posts.length) {
      const post = $posts[bottomView];
      const $post = $(post);

      if (!$post) {
        break;
      }

      const viewTop = $post.offset().top;
      const postHeight = $post.outerHeight(true);
      const viewBottom = Math.ceil(viewTop + postHeight);

      allAbove = allAbove && viewTop < topCheck;

      if (viewTop > viewportBottom) {
        break;
      }

      if (viewBottom >= windowTop && viewTop <= windowBottom) {
        onscreen.push(bottomView);
      }

      if (
        currentPost === null &&
        ((viewTop <= topCheck && viewBottom >= topCheck) ||
          (lastBottom <= topCheck && viewTop >= topCheck))
      ) {
        percent = (topCheck - viewTop) / postHeight;
        currentPost = bottomView;
      }

      lastBottom = viewBottom;
      nearby.push(bottomView);
      bottomView++;
    }

    if (allAbove) {
      if (percent === null) {
        percent = 1.0;
      }
      if (currentPost === null) {
        currentPost = bottomView - 1;
      }
    }

    const posts = this.posts;
    const refresh = cb => this.queueRerender(cb);
    if (onscreen.length) {
      const first = posts.objectAt(onscreen[0]);
      if (this._topVisible !== first) {
        this._topVisible = first;
        const $body = $("body");
        const elem = $posts[onscreen[0]];
        const elemId = elem.id;
        const $elem = $(elem);
        const elemPos = $elem.position();
        const distToElement = elemPos ? $body.scrollTop() - elemPos.top : 0;

        const topRefresh = () => {
          refresh(() => {
            const $refreshedElem = $(`#${elemId}`);

            // Quickly going back might mean the element is destroyed
            const position = $refreshedElem.position();
            if (position && position.top) {
              $("html, body").scrollTop(position.top + distToElement);
            }
          });
        };
        this.sendAction("topVisibleChanged", {
          post: first,
          refresh: topRefresh
        });
      }

      const last = posts.objectAt(onscreen[onscreen.length - 1]);
      if (this._bottomVisible !== last) {
        this._bottomVisible = last;
        this.sendAction("bottomVisibleChanged", { post: last, refresh });
      }

      const changedPost = this._currentPost !== currentPost;
      if (changedPost) {
        this._currentPost = currentPost;
        const post = posts.objectAt(currentPost);
        this.sendAction("currentPostChanged", { post });
      }

      if (percent !== null) {
        percent = Math.max(0.0, Math.min(1.0, percent));

        if (changedPost || this._currentPercent !== percent) {
          this._currentPercent = percent;
          this.sendAction("currentPostScrolled", { percent });
        }
      }
    } else {
      this._topVisible = null;
      this._bottomVisible = null;
      this._currentPost = null;
      this._currentPercent = null;
    }

    const onscreenPostNumbers = [];
    const prev = this._previouslyNearby;
    const newPrev = {};
    nearby.forEach(idx => {
      const post = posts.objectAt(idx);
      const postNumber = post.post_number;
      delete prev[postNumber];

      if (onscreen.indexOf(idx) !== -1) {
        onscreenPostNumbers.push(postNumber);
      }
      newPrev[postNumber] = post;
      uncloak(post, this);
    });

    Object.keys(prev).forEach(pn => cloak(prev[pn], this));

    this._previouslyNearby = newPrev;
    this.screenTrack.setOnscreen(onscreenPostNumbers);
  },

  _scrollTriggered() {
    Ember.run.scheduleOnce("afterRender", this, this.scrolled);
  },

  didInsertElement() {
    this._super();
    const debouncedScroll = () =>
      Ember.run.debounce(this, this._scrollTriggered, 10);

    this._previouslyNearby = {};

    this.appEvents.on("post-stream:refresh", debouncedScroll);
    $(document).bind("touchmove.post-stream", debouncedScroll);
    $(window).bind("scroll.post-stream", debouncedScroll);
    this._scrollTriggered();

    this.appEvents.on("post-stream:posted", staged => {
      const disableJumpReply = this.currentUser.get("disable_jump_reply");

      this.queueRerender(() => {
        if (staged && !disableJumpReply) {
          const postNumber = staged.get("post_number");
          DiscourseURL.jumpToPost(postNumber, { skipIfOnScreen: true });
        }
      });
    });

    this.$().on("mouseenter.post-stream", "button.widget-button", e => {
      $("button.widget-button").removeClass("d-hover");
      $(e.target).addClass("d-hover");
    });

    this.$().on("mouseleave.post-stream", "button.widget-button", () => {
      $("button.widget-button").removeClass("d-hover");
    });

    this.appEvents.on("post-stream:refresh", args => {
      if (args) {
        if (args.id) {
          this.dirtyKeys.keyDirty(`post-${args.id}`);

          if (args.refreshLikes) {
            this.dirtyKeys.keyDirty(`post-menu-${args.id}`, {
              onRefresh: "refreshLikes"
            });
          }
        } else if (args.force) {
          this.dirtyKeys.forceAll();
        }
      }
      this.queueRerender();
    });
  },

  willDestroyElement() {
    this._super();
    $(document).unbind("touchmove.post-stream");
    $(window).unbind("scroll.post-stream");
    this.appEvents.off("post-stream:refresh");
    this.$().off("mouseenter.post-stream");
    this.$().off("mouseleave.post-stream");
    this.appEvents.off("post-stream:refresh");
    this.appEvents.off("post-stream:posted");
  },

  showModerationHistory(post) {
    this.get("adminTools").showModerationHistory({
      filter: "post",
      post_id: post.id
    });
  }
});
