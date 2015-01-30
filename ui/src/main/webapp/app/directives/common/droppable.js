/**
 * Directive for droppable.
 */

angular.module('dataCollectorApp.commonDirectives')
  .directive("droppable", function () {
    return {
      scope: {
        drop: '&'
      },
      link: function(scope, element) {
        // again we need the native object
        var el = element[0];

        el.addEventListener(
          'dragover',
          function(e) {
            //console.log('droppable dragover');
            e.dataTransfer.dropEffect = 'move';
            // allows us to drop
            if (e.preventDefault) {
              e.preventDefault();
            }
            this.classList.add('over');
            return false;
          },
          false
        );

        el.addEventListener(
          'dragenter',
          function(e) {
            //console.log('droppable dragenter');
            this.classList.add('over');
            return false;
          },
          false
        );

        el.addEventListener(
          'dragleave',
          function(e) {
            //console.log('droppable dragleave');
            this.classList.remove('over');
            return false;
          },
          false
        );

        el.addEventListener(
          'drop',
          function(e) {
            // Stops some browsers from redirecting.
            if (e.stopPropagation) {
              e.stopPropagation();
            }

            this.classList.remove('over');

            var dragData = JSON.parse(e.dataTransfer.getData('dragData'));

            scope.$apply(function(scope) {
              var fn = scope.drop();
              if ('undefined' !== typeof fn) {
                fn(e, dragData);
              }
            });

            return false;
          },
          false
        );
      }
    };
  });