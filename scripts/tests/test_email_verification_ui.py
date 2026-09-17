from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[2]
THEME = ROOT / "deploy/identity/themes/cofco-entry/login"


class EmailVerificationUiTest(unittest.TestCase):
    def test_login_keeps_email_implementation_but_hides_its_selector(self):
        login = (THEME / "login.ftl").read_text()
        script = (THEME / "resources/js/sms-login.js").read_text()

        self.assertIn('id="cofco-sms-tab"', login)
        self.assertIn('id="cofco-email-tab" aria-pressed="false" hidden disabled', login)
        self.assertIn('id="cofco-email-login"', login)
        self.assertIn("tabs.email.addEventListener", script)
        self.assertIn("base + '/api/v1/identity/' + channel + '/' + path", script)
        self.assertIn("emailForm,\n  'email',", script)

    def test_registration_keeps_email_profile_and_handler_but_forces_visible_phone_choice(self):
        register = (THEME / "register.ftl").read_text()
        script = (THEME / "resources/js/registration.js").read_text()

        self.assertIn('value="PHONE" checked', register)
        self.assertIn('value="EMAIL" hidden disabled', register)
        self.assertIn("const email = form.querySelector('[name=\"email\"]')", script)
        self.assertIn("method => method.checked && !method.hidden && !method.disabled", script)
        self.assertIn("method => method.value === draft.verificationMethod && !method.hidden && !method.disabled", script)


if __name__ == "__main__":
    unittest.main()
