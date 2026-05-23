/**
 * PaiCLI · Terminal-First Agent IDE — 官方网站交互脚本
 * Scroll reveal, nav effects, smooth scrolling
 */

document.addEventListener('DOMContentLoaded', () => {
  initScrollReveal();
  initNavScroll();
  initSmoothScroll();
  initStaggeredReveal();
  initTimelineAnimation();
});

/* ── Intersection Observer: Scroll Reveal ─────── */
function initScrollReveal() {
  const observerOptions = {
    root: null,
    rootMargin: '0px 0px -60px 0px',
    threshold: 0.1
  };

  const observer = new IntersectionObserver((entries) => {
    entries.forEach((entry, index) => {
      if (entry.isIntersecting) {
        // Stagger delay based on element index within its container
        const siblings = Array.from(entry.target.parentElement.children)
          .filter(child => child.classList.contains('reveal'));
        const idx = siblings.indexOf(entry.target);
        const delay = Math.min(idx * 80, 400);

        setTimeout(() => {
          entry.target.classList.add('visible');
        }, delay);

        observer.unobserve(entry.target);
      }
    });
  }, observerOptions);

  // Add .reveal class to elements we want to animate
  document.querySelectorAll(
    '.about-card, .feature-card, .install-step, .cmd-card, .tl-item'
  ).forEach(el => {
    el.classList.add('reveal');
    observer.observe(el);
  });

  // Also observe section headers
  document.querySelectorAll('.section-header').forEach(el => {
    el.classList.add('reveal');
    observer.observe(el);
  });
}

/* ── Nav Bar Scroll Effect ────────────────────── */
function initNavScroll() {
  const nav = document.getElementById('nav');

  const updateNav = () => {
    if (window.scrollY > 50) {
      nav.classList.add('scrolled');
    } else {
      nav.classList.remove('scrolled');
    }
  };

  window.addEventListener('scroll', updateNav, { passive: true });
  updateNav(); // initial check
}

/* ── Smooth Scroll for Anchor Links ───────────── */
function initSmoothScroll() {
  document.querySelectorAll('a[href^="#"]').forEach(anchor => {
    anchor.addEventListener('click', (e) => {
      const targetId = anchor.getAttribute('href');
      if (targetId === '#') return;

      const target = document.querySelector(targetId);
      if (target) {
        e.preventDefault();
        target.scrollIntoView({
          behavior: 'smooth',
          block: 'start'
        });
      }
    });
  });
}

/* ── Staggered Reveal for Feature Cards ───────── */
function initStaggeredReveal() {
  const featuresGrid = document.querySelector('.features-grid');
  if (!featuresGrid) return;

  const cards = featuresGrid.querySelectorAll('.feature-card');
  cards.forEach((card, i) => {
    card.style.transitionDelay = `${i * 60}ms`;
  });

  const observer = new IntersectionObserver((entries) => {
    entries.forEach(entry => {
      if (entry.isIntersecting) {
        const visibleCards = entry.target.querySelectorAll('.feature-card');
        visibleCards.forEach((card, i) => {
          setTimeout(() => {
            card.style.opacity = '1';
            card.style.transform = 'translateY(0)';
          }, i * 80);
        });
        observer.unobserve(entry.target);
      }
    });
  }, { threshold: 0.1 });

  // Set initial state and observe
  cards.forEach(card => {
    card.style.opacity = '0';
    card.style.transform = 'translateY(20px)';
    card.style.transition = 'opacity 0.5s ease, transform 0.5s ease';
  });
  observer.observe(featuresGrid);
}

/* ── Timeline Animation ───────────────────────── */
function initTimelineAnimation() {
  const timelineItems = document.querySelectorAll('.tl-item');

  const observer = new IntersectionObserver((entries) => {
    entries.forEach(entry => {
      if (entry.isIntersecting) {
        entry.target.style.opacity = '1';
        entry.target.style.transform = 'translateX(0)';
        observer.unobserve(entry.target);
      }
    });
  }, { threshold: 0.15, rootMargin: '0px 0px -30px 0px' });

  timelineItems.forEach((item, i) => {
    item.style.opacity = '0';
    item.style.transform = 'translateX(-20px)';
    item.style.transition = `opacity 0.5s ease ${i * 40}ms, transform 0.5s ease ${i * 40}ms`;
    observer.observe(item);
  });
}

/* ── Active Nav Link Highlight ────────────────── */
(function() {
  const sections = document.querySelectorAll('section[id]');
  const navLinks = document.querySelectorAll('.nav-links a[href^="#"]');

  const observer = new IntersectionObserver((entries) => {
    entries.forEach(entry => {
      if (entry.isIntersecting) {
        navLinks.forEach(link => {
          link.style.color = '';
        });
        const activeLink = document.querySelector(`.nav-links a[href="#${entry.target.id}"]`);
        if (activeLink) {
          activeLink.style.color = '#f0c040';
        }
      }
    });
  }, { threshold: 0.3 });

  sections.forEach(section => observer.observe(section));
})();
