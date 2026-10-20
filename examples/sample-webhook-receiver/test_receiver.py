import hashlib
import hmac
import unittest
from receiver import verify_signature


class SignatureTests(unittest.TestCase):
    def setUp(self):
        self.secret = "a" * 48
        self.timestamp = "1700000000"
        self.body = b'{"amount":123}'
        self.signature = "v1=" + hmac.new(self.secret.encode(), self.timestamp.encode() + b"." + self.body, hashlib.sha256).hexdigest()

    def test_valid_signature(self):
        self.assertTrue(verify_signature(self.secret, self.timestamp, self.body, self.signature, 1700000000))

    def test_tampering_and_wrong_secret(self):
        self.assertFalse(verify_signature(self.secret, self.timestamp, b'{}', self.signature, 1700000000))
        self.assertFalse(verify_signature("wrong", self.timestamp, self.body, self.signature, 1700000000))

    def test_expired_and_future_timestamps(self):
        self.assertFalse(verify_signature(self.secret, self.timestamp, self.body, self.signature, 1700000301))
        self.assertFalse(verify_signature(self.secret, self.timestamp, self.body, self.signature, 1699999699))

    def test_rotation_and_invalid_format(self):
        self.assertTrue(verify_signature(self.secret, self.timestamp, self.body, "v1=bad," + self.signature, 1700000000))
        self.assertFalse(verify_signature(self.secret, "broken", self.body, self.signature, 1700000000))
        self.assertFalse(verify_signature(self.secret, self.timestamp, self.body, "", 1700000000))


if __name__ == "__main__":
    unittest.main()
